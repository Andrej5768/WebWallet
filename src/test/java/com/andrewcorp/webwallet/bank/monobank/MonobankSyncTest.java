package com.andrewcorp.webwallet.bank.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.bank.BankAccountLink;
import com.andrewcorp.webwallet.bank.BankAccountLinkRepository;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.bank.SyncMode;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.BankAccount;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.ClientInfo;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.Jar;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

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
    private ExchangeRateRepository rates;

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

        when(client.clientInfo(TOKEN))
                .thenReturn(new ClientInfo(
                        "c1",
                        "Test User",
                        List.of(
                                new BankAccount(
                                        "mono-black", 1_000_000, 0, "black", 980, List.of("537541******1234"), "UA1"),
                                new BankAccount(
                                        "mono-iron",
                                        6_000_000,
                                        5_000_000,
                                        "iron",
                                        980,
                                        List.of("444111******7777"),
                                        "UA2"),
                                new BankAccount(
                                        "mono-usd", 10_000, 0, "white", 840, List.of("537541******5555"), "UA3")),
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
        return connections
                .findByUserIdAndProvider(user.getId(), BankProvider.MONOBANK)
                .orElseThrow();
    }

    private BankAccountLink link(String externalId) {
        return links.findByConnectionAndExternalAccountId(connection(), externalId)
                .orElseThrow();
    }

    private Account linkedAccount(String externalId) {
        return tx.execute(status -> {
            Account account = link(externalId).getAccount();
            return account == null ? null : accounts.findById(account.getId()).orElseThrow();
        });
    }

    /** Makes a synchronization due and runs its steps until everything is up to date. */
    private int syncAll() {
        tx.executeWithoutResult(status -> {
            BankConnection connection = connection();
            Instant now = TestClock.INSTANCE.instant();
            if (connection.getNextSyncAt().isAfter(now)) {
                connection.setNextSyncAt(now);
            }
        });
        int steps = 0;
        while (service.syncStep(connection().getId())) {
            steps++;
        }
        return steps;
    }

    private static long hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).getEpochSecond();
    }

    private static StatementItem item(
            String id, long time, String description, Integer mcc, long amount, long balance) {
        return new StatementItem(id, time, description, mcc, false, amount, amount, 980, balance, null, null);
    }

    private List<Transaction> userTransactions() {
        return tx.execute(status -> {
            List<Transaction> all = transactions.findAll().stream()
                    .filter(t -> t.getUserId().equals(user.getId()))
                    .toList();
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
        String oldSecret = connection().getWebhookSecret();

        connect(null);

        // The webhook URL changes, so that the old one cannot be used any more
        assertThat(connection().getWebhookSecret()).isNotEqualTo(oldSecret);

        assertThat(links.count()).isEqualTo(4);
        assertThat(linkedAccount("mono-iron").getId()).isEqualTo(ironAccount);
        // The default history starts 31 days ago
        assertThat(link("mono-black").getSyncedUntil()).isBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    }

    @Test
    void historyCanStartAtMostThreeYearsAgo() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/banks/monobank",
                        Map.of(
                                "token",
                                TOKEN,
                                "since",
                                LocalDate.now().minusYears(4).toString()),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("since"));
        mockMvc.perform(postJson("/api/v1/banks/monobank", Map.of("token", "bad\r\ntoken"), auth))
                .andExpect(status().isBadRequest());
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
        statements.put(
                "mono-black",
                List.of(
                        new StatementItem(
                                "op-income",
                                hoursAgo(30),
                                "Salary",
                                4829,
                                false,
                                500_000,
                                500_000,
                                980,
                                1_500_000,
                                null,
                                "ACME LLC"),
                        item("op-silpo", hoursAgo(20), "Silpo", 5411, -30_051, 1_469_949),
                        new StatementItem(
                                "op-steam",
                                hoursAgo(10),
                                "Steam",
                                5816,
                                false,
                                -90_498,
                                -2_000,
                                840,
                                1_379_451,
                                "game",
                                null)));
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
        return transactions.stream()
                .filter(t -> externalId.equals(t.getExternalId()))
                .findFirst()
                .orElseThrow();
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
        fromZen.setBankId("op-zen");
        transactions.save(fromZen);
        // Entered manually two days earlier than the bank date
        Transaction manual = new Transaction(user.getId());
        manual.setType(TransactionType.EXPENSE);
        manual.setDate(LocalDate.now().minusDays(3));
        manual.setAccount(existingCard);
        manual.setAmount(new BigDecimal("250"));
        Long manualId = transactions.save(manual).getId();

        statements.put(
                "mono-black",
                List.of(
                        item("op-zen", hoursAgo(24), "Shop", 5411, -10_000, 0),
                        item("op-manual", hoursAgo(20), "Cafe", 5812, -25_000, 0),
                        new StatementItem(
                                "op-hold", hoursAgo(10), "Hotel", 7011, true, -100_00, -100_00, 980, 0, null, null)));
        connect(LocalDate.now().minusDays(5));
        syncAll();

        assertThat(userTransactions()).hasSize(3);
        assertThat(transactions.findById(manualId).orElseThrow().getBankId()).isEqualTo("op-manual");

        // The hold is settled with another amount; a repeated synchronization updates it
        statements.put(
                "mono-black",
                List.of(
                        item("op-zen", hoursAgo(24), "Shop", 5411, -10_000, 0),
                        new StatementItem(
                                "op-hold", hoursAgo(10), "Hotel", 7011, false, -120_00, -120_00, 980, 0, null, null)));
        resync("mono-black");

        List<Transaction> after = userTransactions();
        assertThat(after).hasSize(3);
        assertThat(find(after, "op-hold").getAmount()).isEqualByComparingTo("120");
        assertThat(find(after, "op-hold").getSplits().get(0).getAmount()).isEqualByComparingTo("120");
    }

    @Test
    void manualTransferIsMatchedOnBothSides() throws Exception {
        connect(LocalDate.now().minusDays(5));
        Account iron = linkedAccount("mono-iron");
        Transaction manual = new Transaction(user.getId());
        manual.setType(TransactionType.TRANSFER);
        manual.setDate(LocalDate.now().minusDays(1));
        manual.setAccount(existingCard);
        manual.setAmount(new BigDecimal("1000"));
        manual.setToAccount(iron);
        manual.setToAmount(new BigDecimal("1000"));
        Long manualId = transactions.save(manual).getId();
        statements.put("mono-black", List.of(item("op-out", hoursAgo(5), "На залізну картку", 4829, -100_000, 0)));
        statements.put("mono-iron", List.of(item("op-in", hoursAgo(5), "З чорної картки", 4829, 100_000, 7_000_000)));

        syncAll();

        assertThat(userTransactions()).singleElement().satisfies(transfer -> {
            assertThat(transfer.getId()).isEqualTo(manualId);
            assertThat(transfer.getBankId()).isEqualTo("op-out");
            assertThat(transfer.getToBankId()).isEqualTo("op-in");
        });
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
            assertThat(transfer.getToAccount().getId())
                    .isEqualTo(linkedAccount("mono-iron").getId());
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
    void mccRules() throws Exception {
        Category taxi = categories.save(new Category(user.getId(), null, "Taxi", CategoryKind.EXPENSE));
        mockMvc.perform(postJson("/api/v1/categorization-rules", Map.of("categoryId", taxi.getId(), "mcc", 4121), auth))
                .andExpect(status().isCreated());

        statements.put("mono-black", List.of(item("op-uber", hoursAgo(5), "Uber", 4121, -15_000, 0)));
        connect(LocalDate.now().minusDays(5));
        syncAll();

        assertThat(find(userTransactions(), "op-uber")
                        .getSplits()
                        .get(0)
                        .getCategory()
                        .getId())
                .isEqualTo(taxi.getId());
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

    @Test
    void operationsWithInvalidIdsAreIgnored() throws Exception {
        connect(LocalDate.now().minusDays(5));
        unlinkAllBut("mono-black");
        statements.put(
                "mono-black",
                List.of(
                        item("", hoursAgo(3), "Empty id", 5814, -100, 0),
                        item("50%_off", hoursAgo(2), "Wildcards", 5814, -200, 0),
                        item("op-ok_1", hoursAgo(1), "Coffee", 5814, -300, 0)));

        syncAll();

        assertThat(userTransactions())
                .singleElement()
                .satisfies(t -> assertThat(t.getExternalId()).isEqualTo("op-ok_1"));
    }

    @Test
    void statementIsDroppedWhenTheAccountIsUnlinkedWhileTheBankIsAsked() throws Exception {
        connect(LocalDate.now().minusDays(5));
        unlinkAllBut("mono-black");
        when(client.statement(eq(TOKEN), eq("mono-black"), any(), any())).thenAnswer(invocation -> {
            unlinkAllBut("none");
            return List.of(item("op-late", hoursAgo(1), "Coffee", 5814, -100, 0));
        });

        assertThat(service.syncStep(connection().getId())).isTrue();

        assertThat(userTransactions()).isEmpty();
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
        statements.put(
                "mono-iron",
                List.of(
                        item("op-1", hoursAgo(30), "Shop", 5411, -50_000, 6_500_000),
                        item("op-2", hoursAgo(10), "Shop", 5411, -20_000, 6_480_000)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        Account iron = linkedAccount("mono-iron");
        // Own funds: 64 800 on the card minus the 50 000 credit limit
        assertThat(accountService.balances(user.getId()).get(iron.getId())).isEqualByComparingTo("14800");
        // The alignment is done once: a correction by the user is kept by later synchronizations
        tx.executeWithoutResult(
                status -> accounts.findById(iron.getId()).orElseThrow().setOpeningBalance(new BigDecimal("1000")));
        resync("mono-iron");
        assertThat(linkedAccount("mono-iron").getOpeningBalance()).isEqualByComparingTo("1000");
        // An existing account is not adjusted
        assertThat(accountService.balances(user.getId()).get(existingCard.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    void raisedCreditLimitIsReadAgainTheNextDay() throws Exception {
        connect(LocalDate.now().minusDays(5));
        syncAll();
        assertThat(link("mono-iron").getBankCreditLimit()).isEqualByComparingTo("50000");
        when(client.clientInfo(TOKEN))
                .thenReturn(new ClientInfo(
                        "c1",
                        "Test User",
                        List.of(new BankAccount(
                                "mono-iron", 8_000_000, 7_000_000, "iron", 980, List.of("444111******7777"), "UA2")),
                        List.of()));

        TestClock.INSTANCE.set(Instant.now().plus(25, ChronoUnit.HOURS));
        syncAll();

        assertThat(link("mono-iron").getBankCreditLimit()).isEqualByComparingTo("70000");
    }

    // webhook

    @Test
    void webhookImportsOperation() throws Exception {
        connect(LocalDate.now().minusDays(5));
        // Only its hash is stored; Monobank gets the secret in the URL when the webhook is registered
        String secret = "webhook-secret-of-the-test";
        tx.executeWithoutResult(status -> connection().setWebhookSecret(TokenHasher.hash(secret)));

        mockMvc.perform(get("/api/v1/banks/monobank/webhook/" + secret)).andExpect(status().isOk());
        mockMvc.perform(postJson(
                        "/api/v1/banks/monobank/webhook/" + secret,
                        Map.of(
                                "type",
                                "StatementItem",
                                "data",
                                Map.of(
                                        "account",
                                        "mono-black",
                                        "statementItem",
                                        Map.of(
                                                "id",
                                                "op-push",
                                                "time",
                                                hoursAgo(1),
                                                "description",
                                                "Coffee",
                                                "mcc",
                                                5814,
                                                "hold",
                                                true,
                                                "amount",
                                                -6_500,
                                                "operationAmount",
                                                -6_500,
                                                "currencyCode",
                                                980,
                                                "balance",
                                                993_500)))))
                .andExpect(status().isOk());
        // Unknown secrets and accounts are ignored
        mockMvc.perform(postJson(
                        "/api/v1/banks/monobank/webhook/unknown",
                        Map.of(
                                "type",
                                "StatementItem",
                                "data",
                                Map.of(
                                        "account",
                                        "mono-black",
                                        "statementItem",
                                        Map.of(
                                                "id",
                                                "op-other",
                                                "time",
                                                hoursAgo(1),
                                                "description",
                                                "Coffee",
                                                "mcc",
                                                5814,
                                                "hold",
                                                false,
                                                "amount",
                                                -100,
                                                "operationAmount",
                                                -100,
                                                "currencyCode",
                                                980,
                                                "balance",
                                                0)))))
                .andExpect(status().isOk());

        assertThat(userTransactions())
                .singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("65"));
        assertThat(link("mono-black").getBankBalance()).isEqualByComparingTo("9935");
    }

    @Test
    void webhookIsRegisteredOnlyInTheWebhookMode() throws Exception {
        connect(LocalDate.now());
        syncAll();
        // Without the public URL of the server there is no webhook
        mockMvc.perform(putJson("/api/v1/banks/monobank/schedule", Map.of("syncMode", "WEBHOOK"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("syncMode"));

        // The service is a transactional proxy; the field lives in the target object
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "publicUrl", "https://wallet.test");
        try {
            // The default INTERVAL mode does not register it
            assertThat(service.syncStep(connection().getId())).isFalse();
            verify(client, never()).setWebhook(anyString(), anyString());

            mockMvc.perform(putJson("/api/v1/banks/monobank/schedule", Map.of("syncMode", "WEBHOOK"), auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.syncMode").value("WEBHOOK"));
            assertThat(service.dueConnections()).isEmpty();
            tx.executeWithoutResult(
                    status -> connection().setLastRequestAt(Instant.now().minus(2, ChronoUnit.MINUTES)));
            assertThat(service.dueConnections()).containsExactly(connection().getId());
            assertThat(service.syncStep(connection().getId())).isTrue();
            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(client).setWebhook(eq(TOKEN), url.capture());
            String prefix = "https://wallet.test/api/v1/banks/monobank/webhook/";
            assertThat(url.getValue()).startsWith(prefix);
            assertThat(connection().getWebhookSecret())
                    .isEqualTo(TokenHasher.hash(url.getValue().substring(prefix.length())));
            mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                    .andExpect(jsonPath("$.webhook").value(true));

            // Another mode removes the webhook with an empty URL
            mockMvc.perform(putJson("/api/v1/banks/monobank/schedule", Map.of("syncMode", "DAILY"), auth))
                    .andExpect(status().isOk());
            assertThat(service.syncStep(connection().getId())).isTrue();
            verify(client).setWebhook(TOKEN, "");
            assertThat(connection().isWebhookRegistered()).isFalse();
        } finally {
            ReflectionTestUtils.setField(target, "publicUrl", "");
        }
    }

    // schedule

    @Test
    void newConnectionHasTheDefaultSchedule() throws Exception {
        connect(LocalDate.now().minusDays(5));

        mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncMode").value("INTERVAL"))
                .andExpect(jsonPath("$.windowStart").value("08:00"))
                .andExpect(jsonPath("$.windowEnd").value("23:00"))
                .andExpect(jsonPath("$.dailyAt").value("06:00"))
                .andExpect(jsonPath("$.nextSyncAt").exists());
        // The history is loaded right away
        assertThat(connection().getNextSyncAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void synchronizationFollowsTheSchedule() throws Exception {
        LocalDate day = LocalDate.now(SyncSchedule.ZONE).plusDays(1);
        TestClock.INSTANCE.set(at(day, "10:07"));
        connect(LocalDate.now().minusDays(5));
        assertThat(syncAll()).isEqualTo(4);
        // Every account was requested: the next synchronization is the next slot of the window
        assertThat(connection().getNextSyncAt()).isEqualTo(at(day, "10:15"));
        assertThat(service.syncStep(connection().getId())).isFalse();

        TestClock.INSTANCE.set(at(day, "10:14"));
        tx.executeWithoutResult(status -> connection().setLastRequestAt(at(day, "10:10")));
        assertThat(service.dueConnections()).isEmpty();

        TestClock.INSTANCE.set(at(day, "10:15:30"));
        assertThat(service.dueConnections()).containsExactly(connection().getId());
        int requests = 0;
        while (service.syncStep(connection().getId())) {
            requests++;
        }
        assertThat(requests).isEqualTo(4);
        assertThat(connection().getNextSyncAt()).isEqualTo(at(day, "10:30"));

        // Once a day
        Map<String, Object> daily = json(
                putJson("/api/v1/banks/monobank/schedule", Map.of("syncMode", "DAILY", "dailyAt", "07:30"), auth), 200);
        assertThat(daily).containsEntry("syncMode", "DAILY").containsEntry("dailyAt", "07:30");
        assertThat(connection().getNextSyncAt()).isEqualTo(at(day.plusDays(1), "07:30"));

        // Another window: after its end the next synchronization is the one at night
        TestClock.INSTANCE.set(at(day, "20:00"));
        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/schedule",
                        Map.of("syncMode", "INTERVAL", "windowStart", "09:00", "windowEnd", "18:00"),
                        auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowStart").value("09:00"))
                .andExpect(jsonPath("$.windowEnd").value("18:00"))
                .andExpect(jsonPath("$.dailyAt").value("07:30"));
        assertThat(connection().getNextSyncAt()).isEqualTo(at(day.plusDays(1), "01:30"));
    }

    @Test
    void scheduleIsValidated() throws Exception {
        mockMvc.perform(putJson("/api/v1/banks/monobank/schedule", Map.of("syncMode", "DAILY"), auth))
                .andExpect(status().isNotFound());
        connect(LocalDate.now().minusDays(5));

        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/schedule",
                        Map.of("syncMode", "INTERVAL", "windowStart", "20:00", "windowEnd", "09:00"),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("windowEnd"));
        // The end is checked against the saved start
        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/schedule", Map.of("syncMode", "INTERVAL", "windowEnd", "07:00"), auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(putJson("/api/v1/banks/monobank/schedule", Map.of("windowStart", "09:00"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("syncMode"));
        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/schedule", Map.of("syncMode", "DAILY", "dailyAt", "25:00"), auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postJson(
                        "/api/v1/banks/monobank",
                        Map.of("token", TOKEN, "windowStart", "10:00", "windowEnd", "10:00"),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("windowEnd"));
    }

    @Test
    void scheduleIsSetOnConnectAndKeptOnReconnect() throws Exception {
        json(
                postJson(
                        "/api/v1/banks/monobank",
                        Map.of("token", TOKEN, "syncMode", "DAILY", "dailyAt", "05:45:30"),
                        auth),
                200);
        assertThat(connection().getSyncMode()).isEqualTo(SyncMode.DAILY);
        assertThat(connection().getDailyAt()).isEqualTo(LocalTime.of(5, 45));

        Map<String, Object> reconnected = connect(null);

        assertThat(reconnected).containsEntry("syncMode", "DAILY").containsEntry("dailyAt", "05:45");
    }

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(SyncSchedule.ZONE).toInstant();
    }

    // management

    @Test
    void linkAccounts() throws Exception {
        connect(LocalDate.now().minusDays(5));
        Long blackLink = link("mono-black").getId();
        Account usdAccount = linkedAccount("mono-usd");
        Account otherUah = accounts.save(new Account(user.getId(), "Other", AccountType.CARD, "UAH"));

        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/accounts/" + blackLink, Map.of("accountId", usdAccount.getId()), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"));
        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/accounts/" + blackLink,
                        Map.of("accountId", linkedAccount("mono-iron").getId()),
                        auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(putJson(
                        "/api/v1/banks/monobank/accounts/" + blackLink, Map.of("accountId", otherUah.getId()), auth))
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
    void reconnectAfterDisconnectReusesTheCreatedAccounts() throws Exception {
        connect(LocalDate.now().minusDays(5));
        Long jarAccount = linkedAccount("mono-jar").getId();
        long accountCount = accounts.count();
        mockMvc.perform(delete("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(status().isNoContent());

        connect(LocalDate.now().minusDays(5));

        assertThat(accounts.count()).isEqualTo(accountCount);
        assertThat(linkedAccount("mono-jar").getId()).isEqualTo(jarAccount);
    }

    @Test
    void settledAndCancelledHoldsAreUpdatedByTheNextSynchronization() throws Exception {
        connect(LocalDate.now().minusDays(5));
        unlinkAllBut("mono-black");
        statements.put(
                "mono-black",
                List.of(
                        new StatementItem(
                                "op-hotel", hoursAgo(50), "Hotel", 7011, true, -100_00, -100_00, 980, 0, null, null),
                        new StatementItem(
                                "op-taxi", hoursAgo(49), "Taxi", 4121, true, -50_00, -50_00, 980, 0, null, null)));
        syncAll();
        assertThat(userTransactions()).hasSize(2);

        // Later: the hotel settles with another amount, the taxi payment is cancelled
        statements.put(
                "mono-black",
                List.of(new StatementItem(
                        "op-hotel", hoursAgo(50), "Hotel", 7011, false, -120_00, -120_00, 980, 0, null, null)));
        tx.executeWithoutResult(
                status -> link("mono-black").setSyncedUntil(Instant.now().minus(10, ChronoUnit.MINUTES)));
        syncAll();

        assertThat(userTransactions()).singleElement().satisfies(hotel -> {
            assertThat(hotel.getExternalId()).isEqualTo("op-hotel");
            assertThat(hotel.getAmount()).isEqualByComparingTo("120");
            assertThat(hotel.isHold()).isFalse();
        });
    }

    @Test
    void editingAMergedTransferReleasesTheOtherHalf() throws Exception {
        statements.put("mono-black", List.of(item("op-out", hoursAgo(5), "На залізну картку", 4829, -100_000, 0)));
        statements.put("mono-iron", List.of(item("op-in", hoursAgo(5), "З чорної картки", 4829, 100_000, 7_000_000)));
        connect(LocalDate.now().minusDays(5));
        syncAll();
        Transaction transfer = userTransactions().get(0);

        // The user decides it was a payment, not a transfer to the own card
        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + transfer.getId(),
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                transfer.getDate().toString(),
                                "accountId",
                                existingCard.getId(),
                                "amount",
                                1000),
                        auth))
                .andExpect(status().isOk());
        assertThat(transactions.findById(transfer.getId()).orElseThrow().getToBankId())
                .isNull();
        laterSync("mono-black");
        laterSync("mono-iron");
        assertThat(connection().getLastError()).isNull();
        // The money still arrived on the other card: the status shows the missing income of that card
        mockMvc.perform(get("/api/v1/banks/monobank").header("Authorization", auth))
                .andExpect(jsonPath("$.accounts[?(@.title == 'Monobank iron *7777')].difference")
                        .value(1000.0));
    }

    @Test
    void paymentsToAndFromOtherPeopleAreNotTakenForATransfer() throws Exception {
        // Sent 1 000 to a friend from one card, got 980 from someone else on another card the same day
        statements.put("mono-black", List.of(item("op-to-friend", hoursAgo(5), "Олені", 4829, -100_000, 0)));
        statements.put("mono-iron", List.of(item("op-from-other", hoursAgo(4), "Від Петра", 4829, 98_000, 7_000_000)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        assertThat(userTransactions()).hasSize(2).noneMatch(t -> t.getType() == TransactionType.TRANSFER);
    }

    @Test
    void currencyExchangeBetweenOwnCardsBecomesOneTransfer() throws Exception {
        rates.save(new ExchangeRate("USD", LocalDate.now().minusDays(1), new BigDecimal("41")));
        rates.save(new ExchangeRate("USD", LocalDate.now(), new BigDecimal("41")));
        // 4 150 UAH bought 100 USD at the bank rate of 41.5
        statements.put("mono-black", List.of(item("op-uah", hoursAgo(5), "Обмін валюти", 4829, -415_000, 0)));
        statements.put(
                "mono-usd",
                List.of(new StatementItem(
                        "op-usd", hoursAgo(5), "Обмін валюти", 4829, false, 10_000, 10_000, 840, 20_000, null, null)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        assertThat(userTransactions()).singleElement().satisfies(transfer -> {
            assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
            assertThat(transfer.getAmount()).isEqualByComparingTo("4150");
            assertThat(transfer.getToAmount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void refundFromAMerchantGoesToTheExpenseCategory() throws Exception {
        Long groceries =
                id(json(postJson("/api/v1/categories", Map.of("name", "Groceries", "kind", "EXPENSE"), auth), 201));
        json(postJson("/api/v1/categorization-rules", Map.of("categoryId", groceries, "mcc", 5411), auth), 201);
        statements.put("mono-black", List.of(item("op-refund", hoursAgo(5), "Silpo", 5411, 5_000, 0)));
        connect(LocalDate.now().minusDays(5));

        syncAll();

        assertThat(userTransactions()).singleElement().satisfies(refund -> {
            assertThat(refund.getType()).isEqualTo(TransactionType.INCOME);
            assertThat(refund.getSplits().get(0).getCategory().getId()).isEqualTo(groceries);
        });
    }

    /** Lets the time of one synchronization interval pass, so that the next one requests the recent days again. */
    private void laterSync(String externalId) {
        tx.executeWithoutResult(
                status -> link(externalId).setSyncedUntil(Instant.now().minus(10, ChronoUnit.MINUTES)));
        syncAll();
    }

    @Test
    void recentOperationsTheUserDeletedOrMovedDoNotComeBack() throws Exception {
        connect(LocalDate.now().minusDays(5));
        unlinkAllBut("mono-black");
        Long otherAccount = accounts.save(new Account(user.getId(), "Cash", AccountType.CASH, "UAH"))
                .getId();
        statements.put(
                "mono-black",
                List.of(
                        item("op-duplicate", hoursAgo(30), "Shop", 5411, -10_000, 0),
                        item("op-moved", hoursAgo(20), "Cafe", 5812, -25_000, 0),
                        item("op-fixed", hoursAgo(10), "Taxi", 4121, -5_000, 0)));
        syncAll();
        List<Transaction> imported = userTransactions();

        mockMvc.perform(delete("/api/v1/transactions/"
                                + find(imported, "op-duplicate").getId())
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());
        Transaction moved = find(imported, "op-moved");
        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + moved.getId(),
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                moved.getDate().toString(),
                                "accountId",
                                otherAccount,
                                "amount",
                                250),
                        auth))
                .andExpect(status().isOk());
        // The user corrects the amount of an operation that is not a hold
        Transaction fixed = find(imported, "op-fixed");
        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + fixed.getId(),
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                fixed.getDate().toString(),
                                "accountId",
                                existingCard.getId(),
                                "amount",
                                55),
                        auth))
                .andExpect(status().isOk());

        laterSync("mono-black");

        List<Transaction> after = userTransactions();
        assertThat(after).hasSize(2);
        assertThat(after)
                .filteredOn(t -> t.getId().equals(moved.getId()))
                .singleElement()
                .satisfies(t -> assertThat(t.getAccount().getId()).isEqualTo(otherAccount));
        assertThat(transactions.findById(fixed.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("55");
        assertThat(connection().getLastError()).isNull();
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
        assertThat(service.dueConnections()).isEmpty();

        tx.executeWithoutResult(
                status -> connection().setLastRequestAt(Instant.now().minus(2, ChronoUnit.MINUTES)));
        doThrow(new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE))
                .when(client)
                .statement(anyString(), anyString(), any(), any());
        task.sync();

        assertThat(connection().getLastError()).isEqualTo("EXTERNAL_SERVICE_UNAVAILABLE");
        assertThat(service.dueConnections()).isEmpty();
    }
}
