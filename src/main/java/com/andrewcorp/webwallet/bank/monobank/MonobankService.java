package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.bank.BankAccountLink;
import com.andrewcorp.webwallet.bank.BankAccountLinkRepository;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.BankAccount;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.ClientInfo;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.Jar;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectRequest;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectionResponse;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.LinkResponse;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Connects Monobank accounts and imports their operations. Monobank allows one request per minute per token, so the
 * history is loaded step by step in the background: every {@link #syncStep} makes at most one request.
 */
@Service
public class MonobankService {

    static final String SOURCE = "MONOBANK";
    /** The longest period of one statement request (31 days and 1 hour, minus a margin). */
    static final Duration MAX_STATEMENT_PERIOD = Duration.ofDays(31);
    /** Accounts synchronized more recently than this are not requested again. */
    static final Duration SYNC_INTERVAL = Duration.ofMinutes(5);
    private static final int DEFAULT_HISTORY_DAYS = 31;
    private static final String WEBHOOK_PATH = "/api/v1/banks/monobank/webhook/";

    private final BankConnectionRepository connections;
    private final BankAccountLinkRepository links;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final MonobankClient client;
    private final MonobankStatementImporter importer;
    private final SecretCipher cipher;
    private final EntityManager entityManager;
    private final Clock clock;
    private final String publicUrl;

    public MonobankService(BankConnectionRepository connections, BankAccountLinkRepository links,
                           AccountRepository accounts, AccountService accountService, MonobankClient client,
                           MonobankStatementImporter importer, SecretCipher cipher, EntityManager entityManager,
                           Clock clock, @Value("${app.public-url:}") String publicUrl) {
        this.connections = connections;
        this.links = links;
        this.accounts = accounts;
        this.accountService = accountService;
        this.client = client;
        this.importer = importer;
        this.cipher = cipher;
        this.entityManager = entityManager;
        this.clock = clock;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    /**
     * Connects (or reconnects with a new token) and links every card and jar to an account: an existing account
     * with the same card number, or a new one.
     */
    @Transactional
    public ConnectionResponse connect(Long userId, ConnectRequest request) {
        ClientInfo info = client.clientInfo(request.token());
        Instant now = clock.instant();
        BankConnection connection = connections.findByUserIdAndProvider(userId, BankProvider.MONOBANK)
                .orElseGet(() -> new BankConnection(userId, BankProvider.MONOBANK, "", TokenHasher.newToken()));
        connection.setEncryptedToken(cipher.encrypt(request.token()));
        connection.setClientName(info.name());
        connection.setLastRequestAt(now);
        connection.setLastError(null);
        connection.setWebhookRegistered(false);
        connections.save(connection);

        LocalDate since = request.since() != null ? request.since()
                : LocalDate.now(clock.withZone(MonobankStatementImporter.BANK_ZONE)).minusDays(DEFAULT_HISTORY_DAYS);
        Instant syncFrom = since.atStartOfDay(MonobankStatementImporter.BANK_ZONE).toInstant();
        for (BankAccount bankAccount : list(info.accounts())) {
            linkCard(userId, connection, bankAccount, syncFrom);
        }
        for (Jar jar : list(info.jars())) {
            linkJar(userId, connection, jar, syncFrom);
        }
        return status(connection);
    }

    @Transactional(readOnly = true)
    public ConnectionResponse status(Long userId) {
        return status(connection(userId));
    }

    /** Links a bank account to another WebWallet account, or stops synchronizing it with a null account. */
    @Transactional
    public ConnectionResponse link(Long userId, Long linkId, Long accountId) {
        BankConnection connection = connection(userId);
        BankAccountLink link = links.findById(linkId)
                .filter(candidate -> candidate.getConnection().getId().equals(connection.getId()))
                .orElseThrow(ApiException::notFound);
        if (accountId == null) {
            link.setAccount(null);
            return status(connection);
        }
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.invalid("accountId", "error.notFound"));
        if (!account.getCurrency().equals(link.getCurrency())) {
            throw ApiException.invalid("accountId", "error.bank.currency");
        }
        boolean usedByAnotherLink = links.findByConnectionOrderByIdAsc(connection).stream()
                .anyMatch(other -> !other.getId().equals(link.getId()) && other.getAccount() != null
                        && other.getAccount().getId().equals(accountId));
        if (usedByAnotherLink) {
            throw ApiException.invalid("accountId", "error.bank.accountLinked");
        }
        link.setAccount(account);
        return status(connection);
    }

    /** Removes the connection and the token; imported transactions stay. */
    @Transactional
    public void disconnect(Long userId) {
        connections.delete(connection(userId));
    }

    /** Whether the connection may make a request now without exceeding the Monobank limit. */
    boolean canRequest(BankConnection connection) {
        Instant last = connection.getLastRequestAt();
        return last == null || !last.plus(Duration.ofSeconds(61)).isAfter(clock.instant());
    }

    /**
     * Makes the next request of the connection: registers the webhook if needed, or loads the next period of the
     * account that is the most behind. Returns false when there was nothing to do.
     */
    @Transactional
    public boolean syncStep(Long connectionId) {
        BankConnection connection = connections.findById(connectionId).orElse(null);
        if (connection == null) {
            return false;
        }
        Instant now = clock.instant();
        String token = cipher.decrypt(connection.getEncryptedToken());
        if (!publicUrl.isEmpty() && !connection.isWebhookRegistered()) {
            connection.setLastRequestAt(now);
            client.setWebhook(token, publicUrl + WEBHOOK_PATH + connection.getWebhookSecret());
            connection.setWebhookRegistered(true);
            connection.setLastError(null);
            return true;
        }
        List<BankAccountLink> allLinks = links.findByConnectionOrderByIdAsc(connection);
        Optional<BankAccountLink> next = allLinks.stream()
                .filter(link -> link.getAccount() != null)
                .filter(link -> link.getGapTo() != null || link.getSyncedUntil().isBefore(now.minus(SYNC_INTERVAL)))
                .min(Comparator.comparing(BankAccountLink::getSyncedUntil));
        if (next.isEmpty()) {
            return false;
        }
        BankAccountLink link = next.get();
        Instant from = link.getSyncedUntil();
        Instant to = link.getGapTo() != null ? link.getGapTo() : min(from.plus(MAX_STATEMENT_PERIOD), now);
        connection.setLastRequestAt(now);
        List<StatementItem> items = client.statement(token, link.getExternalAccountId(), from, to);
        connection.setLastError(null);
        importer.importItems(link.getAccount(), ownAccounts(allLinks), items);

        if (items.size() >= MonobankClient.MAX_STATEMENT_ITEMS) {
            Instant oldest = Instant.ofEpochSecond(items.stream().mapToLong(StatementItem::time).min().orElseThrow());
            link.setGap(oldest, link.getGapTarget() != null ? link.getGapTarget() : to);
        } else {
            Instant target = link.getGapTarget() != null ? link.getGapTarget() : to;
            link.setSyncedUntil(target);
            link.setGap(null, null);
            if (!target.isBefore(now)) {
                items.stream().max(Comparator.comparingLong(StatementItem::time))
                        .ifPresent(newest -> link.setBankBalance(ownFunds(link.getAccount(), newest.balance())));
                alignCreatedAccount(link);
            }
        }
        return true;
    }

    /** Imports an operation pushed by Monobank. Unknown secrets and accounts are ignored. */
    @Transactional
    public void handleWebhook(String secret, String externalAccountId, StatementItem item) {
        connections.findByProviderAndWebhookSecret(BankProvider.MONOBANK, secret).ifPresent(connection -> {
            List<BankAccountLink> allLinks = links.findByConnectionOrderByIdAsc(connection);
            allLinks.stream()
                    .filter(link -> link.getExternalAccountId().equals(externalAccountId) && link.getAccount() != null)
                    .findFirst()
                    .ifPresent(link -> {
                        importer.importItems(link.getAccount(), ownAccounts(allLinks), List.of(item));
                        link.setBankBalance(ownFunds(link.getAccount(), item.balance()));
                    });
        });
    }

    /** Ids of the connections that may make a request now. */
    @Transactional(readOnly = true)
    public List<Long> connectionsReadyForRequest() {
        return connections.findByProvider(BankProvider.MONOBANK).stream()
                .filter(this::canRequest)
                .map(BankConnection::getId)
                .toList();
    }

    @Transactional
    public void recordError(Long connectionId, String error) {
        connections.findById(connectionId).ifPresent(connection -> {
            connection.setLastRequestAt(clock.instant());
            connection.setLastError(error);
        });
    }

    private void linkCard(Long userId, BankConnection connection, BankAccount bankAccount, Instant syncFrom) {
        String currency = Currencies.byNumericCode(bankAccount.currencyCode());
        if (currency == null) {
            return;
        }
        List<String> cardNumbers = list(bankAccount.maskedPan()).stream()
                .filter(pan -> pan.length() >= 4)
                .map(pan -> pan.substring(pan.length() - 4))
                .toList();
        String title = "Monobank " + bankAccount.type()
                + (cardNumbers.isEmpty() ? "" : " *" + cardNumbers.get(0));
        BankAccountLink link = link(connection, bankAccount.id(), title, currency, syncFrom);
        BigDecimal creditLimit = Currencies.fromMinorUnits(bankAccount.creditLimit(), currency);
        if (link.getAccount() == null && link.getId() == null) {
            Account account = accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                    .filter(candidate -> candidate.getCurrency().equals(currency))
                    .filter(candidate -> candidate.getCardNumbers() != null
                            && candidate.getCardNumbers().stream().anyMatch(cardNumbers::contains))
                    .filter(candidate -> !isLinked(connection, candidate))
                    .findFirst()
                    .orElseGet(() -> {
                        Account created = new Account(userId, title,
                                creditLimit.signum() > 0 ? AccountType.CREDIT_CARD : AccountType.CARD, currency);
                        created.setExternal(SOURCE, bankAccount.id());
                        created.setCardNumbers(cardNumbers.isEmpty() ? null : cardNumbers);
                        if (creditLimit.signum() > 0) {
                            created.setCreditLimit(creditLimit);
                        }
                        return accounts.save(created);
                    });
            link.setAccount(account);
        }
        // For a credit card the bank balance includes the credit limit
        link.setBankBalance(Currencies.fromMinorUnits(bankAccount.balance(), currency).subtract(creditLimit));
        links.save(link);
    }

    private void linkJar(Long userId, BankConnection connection, Jar jar, Instant syncFrom) {
        String currency = Currencies.byNumericCode(jar.currencyCode());
        if (currency == null) {
            return;
        }
        BankAccountLink link = link(connection, jar.id(), jar.title(), currency, syncFrom);
        if (link.getAccount() == null && link.getId() == null) {
            Account account = new Account(userId, jar.title(), AccountType.SAVINGS, currency);
            account.setExternal(SOURCE, jar.id());
            link.setAccount(accounts.save(account));
        }
        link.setBankBalance(Currencies.fromMinorUnits(jar.balance(), currency));
        links.save(link);
    }

    private BankAccountLink link(BankConnection connection, String externalId, String title, String currency,
                                 Instant syncFrom) {
        BankAccountLink link = links.findByConnectionAndExternalAccountId(connection, externalId)
                .orElseGet(() -> new BankAccountLink(connection, externalId, title, currency, syncFrom));
        link.setTitle(title);
        return link;
    }

    private boolean isLinked(BankConnection connection, Account account) {
        return connection.getId() != null && links.findByConnectionOrderByIdAsc(connection).stream()
                .anyMatch(link -> link.getAccount() != null && link.getAccount().getId().equals(account.getId()));
    }

    /**
     * An account created for the bank account has no history before the first imported operation; its opening
     * balance is set so that the balance equals the bank balance.
     */
    private void alignCreatedAccount(BankAccountLink link) {
        Account account = link.getAccount();
        if (link.getBankBalance() == null || !SOURCE.equals(account.getExternalSource())
                || !link.getExternalAccountId().equals(account.getExternalId())) {
            return;
        }
        entityManager.flush();
        BigDecimal balance = accountService.balances(account.getUserId())
                .getOrDefault(account.getId(), account.getOpeningBalance());
        account.setOpeningBalance(account.getOpeningBalance().add(link.getBankBalance().subtract(balance)));
    }

    private ConnectionResponse status(BankConnection connection) {
        Map<Long, BigDecimal> balances = accountService.balances(connection.getUserId());
        List<LinkResponse> linkResponses = links.findByConnectionOrderByIdAsc(connection).stream()
                .map(link -> new LinkResponse(
                        link.getId(),
                        link.getTitle(),
                        link.getCurrency(),
                        link.getAccount() == null ? null : link.getAccount().getId(),
                        link.getAccount() == null ? null : link.getAccount().getName(),
                        link.getSyncedUntil(),
                        link.getBankBalance(),
                        link.getAccount() == null ? null : balances.get(link.getAccount().getId())))
                .toList();
        return new ConnectionResponse(connection.getClientName(), connection.getCreatedAt(),
                connection.getLastRequestAt(), connection.getLastError(), connection.isWebhookRegistered(), linkResponses);
    }

    private BankConnection connection(Long userId) {
        return connections.findByUserIdAndProvider(userId, BankProvider.MONOBANK).orElseThrow(ApiException::notFound);
    }

    private static List<Account> ownAccounts(List<BankAccountLink> links) {
        return links.stream().map(BankAccountLink::getAccount).filter(Objects::nonNull).toList();
    }

    private static BigDecimal ownFunds(Account account, long balanceMinorUnits) {
        BigDecimal balance = Currencies.fromMinorUnits(balanceMinorUnits, account.getCurrency());
        return account.getCreditLimit() == null ? balance : balance.subtract(account.getCreditLimit());
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list;
    }
}
