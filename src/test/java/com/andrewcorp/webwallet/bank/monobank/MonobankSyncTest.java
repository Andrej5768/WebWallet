package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.bank.BankAccountLink;
import com.andrewcorp.webwallet.bank.BankAccountLinkRepository;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.BankAccount;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.ClientInfo;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.Jar;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonobankSyncTest extends AbstractIntegrationTest {

    private static final String TOKEN = "mono-token";

    @MockitoBean
    private MonobankClient client;

    @Autowired
    private MonobankService service;

    @Autowired
    private MonobankSyncTask task;

    @Autowired
    private BankConnectionRepository connections;

    @Autowired
    private BankAccountLinkRepository links;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private PayeeService payees;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private TransactionTemplate tx;

    private User user;
    private String auth;
    private Account existingCard;
    /** Statement of each Monobank account, returned for any period. */
    private final Map<String, List<StatementItem>> statements = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        Account card = new Account(user.getId(), "Black card from Zen Money", AccountType.CARD, "UAH");
        card.setCardNumbers(List.of("1234"));
        existingCard = accounts.save(card);

        when(client.clientInfo(TOKEN)).thenReturn(new ClientInfo("c1", "Test User", List.of(
                new BankAccount("mono-black", 1_000_000, 0, "black", 980, List.of("537541******1234"), "UA1"),
                new BankAccount("mono-iron", 6_000_000, 5_000_000, "iron", 980, List.of("444111******7777"), "UA2"),
                new BankAccount("mono-usd", 10_000, 0, "white", 840, List.of("537541******5555"), "UA3")),
                List.of(new Jar("mono-jar", "Trip", 980, 50_000))));
        when(client.statement(eq(TOKEN), anyString(), any(), any()))
                .thenAnswer(invocation -> statements.getOrDefault(invocation.<String>getArgument(1), List.of()));
    }

    private Map<String, Object> connect(LocalDate since) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("token", TOKEN));
        body.put("since", since == null ? null : since.toString());
        return json(postJson("/api/v1/banks/monobank", body, auth), 200);
    }

    private BankConnection connection() {
        return connections.findByUserIdAndProvider(user.getId(), BankProvider.MONOBANK).orElseThrow();
    }

    private BankAccountLink link(String externalId) {
        return links.findByConnectionAndExternalAccountId(connection(), externalId).orElseThrow();
    }

    private Account linkedAccount(String externalId) {
        return tx.execute(status -> {
            Account account = link(externalId).getAccount();
            return account == null ? null : accounts.findById(account.getId()).orElseThrow();
        });
    }

    /** Runs synchronization steps until everything is up to date. */
    private int syncAll() {
        int steps = 0;
        while (service.syncStep(connection().getId())) {
            steps++;
        }
        return steps;
    }

    private static long hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).getEpochSecond();
    }

    private static StatementItem item(String id, long time, String description, Integer mcc, long amount, long balance) {
        return new StatementItem(id, time, description, mcc, false, amount, amount, 980, balance, null, null);
    }

    private List<Transaction> userTransactions() {
        return tx.execute(status -> {
            List<Transaction> all = transactions.findAll().stream()
                    .filter(t -> t.getUserId().equals(user.getId())).toList();
            // Touch lazy associations inside the transaction
            all.forEach(t -> {
                t.getAccount().getId();
                if (t.getToAccount() != null) {
                    t.getToAccount().getId();
                }
                if (t.getPayee() != null) {
                    t.getPayee().getName();
                }
                t.getSplits().forEach(split -> {
                    if (split.getCategory() != null) {
                        split.getCategory().getId();
                    }
                });
            });
            return all;
        });
    }

    // connecting

    @Test
    void connectLinksCardsToAccounts() throws Exception {
        Map<String, Object> result = connect(LocalDate.now().minusDays(5));

        assertThat(result).containsEntry("clientName", "Test User").containsEntry("webhook", false);
        assertThat((List<?>) result.get("accounts")).hasSize(4);
        // The card with the same last digits is linked to the existing account
        assertThat(linkedAccount("mono-black").getId()).isEqualTo(existingCard.getId());
        // Other cards and the jar get new accounts
        Account iron = linkedAccount("mono-iron");
        assertThat(iron.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(iron.getCreditLimit()).isEqualByComparingTo("50000");
        assertThat(iron.getCardNumbers()).containsExactly("7777");
        assertThat(linkedAccount("mono-usd").getCurrency()).isEqualTo("USD");
        Account jar = linkedAccount("mono-jar");
        assertThat(jar.getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(jar.isInBudget()).isFalse();
        // Bank balance of a credit card excludes the credit limit
        assertThat(link("mono-iron").getBankBalance()).isEqualByComparingTo("10000");
        // The token is stored encrypted
        assertThat(connection().getEncryptedToken()).doesNotContain(TOKEN);
        assertThat(cipher.decrypt(connection().getEncryptedToken())).isEqualTo(TOKEN);
    }

    @Test
    void reconnectKeepsLinks() throws Exception {
        connect(null);
        Long ironAccount = linkedAccount("mono-iron").getId();

        connect(null);

        assertThat(links.count()).isEqualTo(4);
        assertThat(linkedAccount("mono-iron").getId()).isEqualTo(ironAccount);
        // The default history starts 31 days ago
        assertThat(link("mono-black").getSyncedUntil()).isBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    }

    @Test
    void connectWithRejectedToken() throws Exception {
        when(client.clientInfo("bad")).thenThrow(new ApiException(ErrorCode.INVALID_EXTERNAL_TOKEN));

        mockMvc.perform(postJson("/api/v1/banks/monobank", Map.of("token", "bad"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXTERNAL_TOKEN"));
    }

    // synchronization

    @Test
    void importsOperations() throws Exception {
        statements.put("mono-black", List.of(
                new StatementItem("op-income", hoursAgo(30), "Salary", 4829, false, 500_000, 500_000, 980, 1_500_000,
                        null, "ACME LLC"),
                item("op-silpo", hoursAgo(20), "Silpo", 5411, -30_051, 1_469_949),
                new StatementItem("op-steam", hoursAgo(10), "Steam", 5816, false, -90_498, -2_000, 840, 1_379_451,
                        "game", null)));
        connect(LocalDate.now().minusDays(5));

        assertThat(syncAll()).isEqualTo(4);

        List<Transaction> imported = userTransactions();
        assertThat(imported).hasSize(3).allSatisfy(t -> {
            assertThat(t.getSource()).isEqualTo(TransactionSource.MONOBANK);
            assertThat(t.getAccount().getId()).isEqualTo(existingCard.getId());
        });
        Transaction income = find(imported, "op-income");
        assertThat(income.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(income.getAmount()).isEqualByComparingTo("5000");
        assertThat(income.getPayee().getName()).isEqualTo("ACME LLC");
        Transaction silpo = find(imported, "op-silpo");
        assertThat(silpo.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(silpo.getAmount()).isEqualByComparingTo("300.51");
        assertThat(silpo.getMcc()).isEqualTo((short) 5411);
        Transaction steam = find(imported, "op-steam");
        assertThat(steam.getOriginalAmount()).isEqualByComparingTo("20");
        assertThat(steam.getOriginalCurrency()).isEqualTo("USD");
        assertThat(steam.getComment()).isEqualTo("game");

        // Everything is up to date: no more requests
        assertThat(service.syncStep(connection().getId())).isFalse();
        assertThat(link("mono-black").getBankBalance()).isEqualByComparingTo("13794.51");
    }

    private static Transaction find(List<Transaction> transactions, String externalId) {
        return transactions.stream().filter(t -> externalId.equals(t.getExternalId())).findFirst().orElseThrow();
    }

    @Test
    void doesNotDuplicateKnownOperations() throws Exception {
        // Imported from Zen Money with the bank id
        Transaction fromZen = new Transaction(user.getId());
        fromZen.setSource(TransactionSource.ZENMONEY);
        fromZen.setType(TransactionType.EXPENSE);
        fromZen.setDate(LocalDate.now().minusDays(1));
        fromZen.setAccount(existingCard);
        fromZen.setAmount(new BigDecimal("100"));
        fromZen.setBankReference("[monobank][reg]-op-zen");
        transactions.save(fromZen);
        // Entered manually two days earlier than the bank date
        Transaction manual = new Transaction(user.getId());
        manual.setType(TransactionType.EXPENSE);
        manual.setDate(LocalDate.now().minusDays(3));
        manual.setAccount(existingCard);
        manual.setAmount(new BigDecimal("250"));
        Long manualId = transactions.save(manual).getId();

        statements.put("mono-black", List.of(
                item("op-zen", hoursAgo(24), "Shop", 5411, -10_000, 0),
                item("op-manual", hoursAgo(20), "Cafe", 5812, -25_000, 0),
                new StatementItem("op-hold", hoursAgo(10), "Hotel", 7011, true, -100_00, -100_00, 980, 0, null, null)));
        connect(LocalDate.now().minusDays(5));
        syncAll();

        assertThat(userTransactions()).hasSize(3);
        assertThat(transactions.findById(manualId).orElseThrow().getBankReference()).isEqualTo("op-manual");

        // The hold is settled with another amount; a repeated synchronization updates it
        statements.put("mono-black", List.of(
                item("op-zen", hoursAgo(24), "Shop", 5411, -10_000, 0),
                new StatementItem("op-hold", hoursAgo(10), "Hotel", 7011, false, -120_00, -120_00, 980, 0, null, null)));
        resync("mono-black");

        List<Transaction> after = userTransactions();
        assertThat(after).hasSize(3);
        assertThat(find(after, "op-hold").getAmount()).isEqualByComparingTo("120");
        assertThat(find(after, "op-hold").getSplits().get(0).getAmount()).isEqualByComparingTo("120");
    }

    private void resync(String externalId) {
        tx.executeWithoutResult(status -> {
            BankAccountLink link = link(externalId);
            link.setSyncedUntil(Instant.now().minus(5, ChronoUnit.DAYS));
            links.save(link);
        });
        syncAll();
    }

    @Test
    void transferBetweenOwnCardsBecomesOneTransfer() throws Exception {
        statements.put("mono-black", List.of(item("op-out", hoursAgo(5), "На залізну картку", 4829, -100_000, 0)));
        statements.put("mono-iron", List.of(item("op-in", hoursAgo(5), "З чорної картки", 4829, 100_000, 7_000_000)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        List<Transaction> imported = userTransactions();
        assertThat(imported).singleElement().satisfies(transfer -> {
            assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
            assertThat(transfer.getAccount().getId()).isEqualTo(existingCard.getId());
            assertThat(transfer.getToAccount().getId()).isEqualTo(linkedAccount("mono-iron").getId());
            assertThat(transfer.getToAmount()).isEqualByComparingTo("1000");
            assertThat(transfer.getSplits()).isEmpty();
        });

        // Synchronizing again does not split it up
        resync("mono-black");
        resync("mono-iron");
        assertThat(userTransactions()).hasSize(1);
    }

    @Test
    void categoryIsLearnedFromPayee() throws Exception {
        Category groceries = categories.save(new Category(user.getId(), null, "Groceries", CategoryKind.EXPENSE));
        Transaction earlier = new Transaction(user.getId());
        earlier.setType(TransactionType.EXPENSE);
        earlier.setDate(LocalDate.now().minusDays(40));
        earlier.setAccount(existingCard);
        earlier.setAmount(new BigDecimal("10"));
        earlier.setPayee(payees.findOrCreate(user.getId(), "Silpo"));
        earlier.replaceSplits(List.of(new Transaction.SplitData(groceries, new BigDecimal("10"), null)));
        transactions.save(earlier);

        statements.put("mono-black", List.of(item("op-silpo", hoursAgo(5), "SILPO", 5411, -5_000, 0)));
        connect(LocalDate.now().minusDays(5));
        syncAll();

        Transaction imported = find(userTransactions(), "op-silpo");
        assertThat(imported.getSplits().get(0).getCategory().getId()).isEqualTo(groceries.getId());
    }

    @Test
    void moreThan500OperationsAreLoadedInSeveralRequests() throws Exception {
        long start = hoursAgo(20 * 24);
        List<StatementItem> newest = new ArrayList<>(IntStream.range(0, 500)
                .mapToObj(i -> item("op-new-" + i, start + 10_000 + i * 60L, "Shop", 5411, -100, 0))
                .toList());
        List<StatementItem> oldest = List.of(item("op-old", start + 100, "Shop", 5411, -100, 0));
        when(client.statement(eq(TOKEN), eq("mono-black"), any(), any()))
                .thenReturn(newest)
                .thenReturn(oldest);
        connect(LocalDate.now().minusDays(25));
        unlinkAllBut("mono-black");

        syncAll();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(client, times(2)).statement(eq(TOKEN), eq("mono-black"), from.capture(), to.capture());
        // The second request covers the period before the oldest operation of the first one
        assertThat(from.getAllValues().get(1)).isEqualTo(from.getAllValues().get(0));
        assertThat(to.getAllValues().get(1)).isEqualTo(Instant.ofEpochSecond(start + 10_000));
        assertThat(userTransactions()).hasSize(501);
        assertThat(link("mono-black").getGapTo()).isNull();
        assertThat(link("mono-black").getSyncedUntil()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    private void unlinkAllBut(String externalId) {
        tx.executeWithoutResult(status -> links.findByConnectionOrderByIdAsc(connection()).stream()
                .filter(link -> !link.getExternalAccountId().equals(externalId))
                .forEach(link -> link.setAccount(null)));
    }

    @Test
    void longHistoryIsLoadedInMonthlyPeriods() throws Exception {
        connect(LocalDate.now().minusDays(70));
        unlinkAllBut("mono-black");

        assertThat(syncAll()).isEqualTo(3);
    }

    @Test
    void balanceOfCreatedAccountMatchesTheBank() throws Exception {
        statements.put("mono-iron", List.of(
                item("op-1", hoursAgo(30), "Shop", 5411, -50_000, 6_500_000),
                item("op-2", hoursAgo(10), "Shop", 5411, -20_000, 6_480_000)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        Account iron = linkedAccount("mono-iron");
        // Own funds: 64 800 on the card minus the 50 000 credit limit
        assertThat(accountService.balances(user.getId()).get(iron.getId())).isEqualByComparingTo("14800");
        // An existing account is not adjusted
        assertThat(accountService.balances(user.getId()).get(existingCard.getId())).isEqualByComparingTo("0");
    }

    // webhook

    @Test
    void webhookImportsOperation() throws Exception {
        connect(LocalDate.now().minusDays(5));
        String secret = connection().getWebhookSecret();

        mockMvc.perform(get("/api/v1/banks/monobank/webhook/" + secret)).andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/banks/monobank/webhook/" + secret, Map.of("type", "StatementItem",
                        "data", Map.of("account", "mono-black", "statementItem", Map.of(
                                "id", "op-push", "time", hoursAgo(1), "description", "Coffee", "mcc", 5814,
                                "hold", true, "amount", -6_500, "operationAmount", -6_500, "currencyCode", 980,
                                "balance", 993_500)))))
                .andExpect(status().isOk());
        // Unknown secrets and accounts are ignored
        mockMvc.perform(postJson("/api/v1/banks/monobank/webhook/unknown", Map.of("type", "StatementItem",
                        "data", Map.of("account", "mono-black", "statementItem", Map.of(
                                "id", "op-other", "time", hoursAgo(1), "description", "Coffee", "mcc", 5814,
                                "hold", false, "amount", -100, "operationAmount", -100, "currencyCode", 980,
                                "balance", 0)))))
                .andExpect(status().isOk());

        assertThat(userTransactions()).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("65"));
        assertThat(link("mono-black").getBankBalance()).isEqualByComparingTo("9935");
    }

    @Test
    void webhookIsRegisteredWhenThePublicUrlIsKnown() throws Exception {
        connect(LocalDate.now());
        // The service is a transactional proxy; the field lives in the target object
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "publicUrl", "https://wallet.test");
        try {
            assertThat(service.syncStep(connection().getId())).isTrue();
        } finally {
            ReflectionTestUtils.setField(target, "publicUrl", "");
        }

        verify(client).setWebhook(TOKEN, "https://wallet.test/api/v1/banks/monobank/webhook/" + connection().getWebhookSecret());
        mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(jsonPath("$.webhook").value(true));
    }

    // management

    @Test
    void linkAccounts() throws Exception {
        connect(LocalDate.now().minusDays(5));
        Long blackLink = link("mono-black").getId();
        Account usdAccount = linkedAccount("mono-usd");
        Account otherUah = accounts.save(new Account(user.getId(), "Other", AccountType.CARD, "UAH"));

        mockMvc.perform(putJson("/api/v1/banks/monobank/accounts/" + blackLink, Map.of("accountId", usdAccount.getId()), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"));
        mockMvc.perform(putJson("/api/v1/banks/monobank/accounts/" + blackLink,
                        Map.of("accountId", linkedAccount("mono-iron").getId()), auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(putJson("/api/v1/banks/monobank/accounts/" + blackLink, Map.of("accountId", otherUah.getId()), auth))
                .andExpect(status().isOk());
        assertThat(linkedAccount("mono-black").getId()).isEqualTo(otherUah.getId());

        Map<String, Object> unlink = new HashMap<>();
        unlink.put("accountId", null);
        mockMvc.perform(putJson("/api/v1/banks/monobank/accounts/" + blackLink, unlink, auth))
                .andExpect(status().isOk());
        assertThat(linkedAccount("mono-black")).isNull();

        mockMvc.perform(putJson("/api/v1/banks/monobank/accounts/" + blackLink, unlink, loginNewUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusAndDisconnect() throws Exception {
        mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(status().isNotFound());
        connect(LocalDate.now().minusDays(5));

        mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(4))
                .andExpect(jsonPath("$.accounts[0].accountId").value(existingCard.getId()));

        mockMvc.perform(delete("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(status().isNoContent());
        assertThat(connections.count()).isZero();
        assertThat(accounts.findById(existingCard.getId())).isPresent();
    }

    @Test
    void syncTaskRespectsRateLimitAndRecordsErrors() throws Exception {
        connect(LocalDate.now().minusDays(5));
        // The connection request was just made
        assertThat(service.connectionsReadyForRequest()).isEmpty();

        tx.executeWithoutResult(status -> connection().setLastRequestAt(Instant.now().minus(2, ChronoUnit.MINUTES)));
        doThrow(new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE))
                .when(client).statement(anyString(), anyString(), any(), any());
        task.sync();

        assertThat(connection().getLastError()).isEqualTo("EXTERNAL_SERVICE_UNAVAILABLE");
        assertThat(service.connectionsReadyForRequest()).isEmpty();
    }
}
