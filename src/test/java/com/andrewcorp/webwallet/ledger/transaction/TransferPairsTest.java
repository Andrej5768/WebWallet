package com.andrewcorp.webwallet.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/** Expenses and incomes of different own accounts that may be one transfer: suggested, merged on the user's word. */
class TransferPairsTest extends AbstractIntegrationTest {

    private static final String HEADER = "date,categoryName,payee,comment,outcomeAccountName,outcome,"
            + "outcomeCurrencyShortTitle,incomeAccountName,income,incomeCurrencyShortTitle,createdDate,changedDate\n";

    /** A transfer that lost 3.5 % on the way, as two operations, and a categorized purchase of a similar amount. */
    private static final String PAIR_CSV = HEADER
            + "2026-09-10,,,To my card,Card A,\"1000,00\",UAH,,,,\"2026-09-10 10:00:00\",\"2026-09-10 10:00:00\"\n"
            + "2026-09-10,,,From my card,,,,Card B,\"965,00\",UAH,\"2026-09-10 10:05:00\",\"2026-09-10 10:05:00\"\n"
            + "2026-09-10,Food,Shop,,Card B,\"990,00\",UAH,,,,\"2026-09-10 12:00:00\",\"2026-09-10 12:00:00\"\n";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    private User user;
    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
    }

    private Map<String, Object> preview(String csv) throws Exception {
        return json(
                multipart("/api/v1/imports/zenmoney/file/preview")
                        .file(new MockMultipartFile(
                                "file", "zen.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", auth),
                200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> preview) {
        return (List<Map<String, Object>>) ((Map<String, Object>) preview.get("operations")).get("items");
    }

    private static Map<String, Object> byComment(Map<String, Object> preview, String comment) {
        return operations(preview).stream()
                .filter(operation -> comment.equals(operation.get("comment")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> suggestions(Map<String, Object> operation) {
        return (List<Map<String, Object>>) operation.get("transferSuggestions");
    }

    private Map<String, Object> confirm(Map<String, Object> preview, List<Map<String, Object>> edits) throws Exception {
        return json(
                postJson(
                        "/api/v1/imports/previews/" + preview.get("id") + "/confirm",
                        Map.of("operations", edits),
                        auth),
                200);
    }

    private List<Transaction> userTransactions() {
        return transactions.findAll().stream()
                .filter(t -> t.getUserId().equals(user.getId()))
                .toList();
    }

    private Transaction ledger(Account account, TransactionType type, String amount, LocalDate date) {
        Transaction transaction = new Transaction(user.getId());
        transaction.setType(type);
        transaction.setDate(date);
        transaction.setAccount(account);
        transaction.setAmount(new BigDecimal(amount));
        transaction.replaceSplits(List.of(new SplitData(null, new BigDecimal(amount), null)));
        return transactions.save(transaction);
    }

    // import preview

    @Test
    void suggestsTheOtherHalfInTheSameFileAndMergesItWhenChosen() throws Exception {
        Map<String, Object> preview = preview(PAIR_CSV);

        Map<String, Object> out = byComment(preview, "To my card");
        Map<String, Object> in = byComment(preview, "From my card");
        assertThat(suggestions(out)).singleElement().satisfies(suggestion -> {
            assertThat(suggestion).containsEntry("operationKey", in.get("key")).containsEntry("exact", false);
            assertThat(new BigDecimal(suggestion.get("difference").toString())).isEqualByComparingTo("-0.035");
        });
        assertThat(suggestions(in))
                .singleElement()
                .satisfies(s -> assertThat(s).containsEntry("operationKey", out.get("key")));
        // A categorized purchase is not a half of a transfer
        assertThat(operations(preview))
                .filteredOn(operation -> "Shop".equals(operation.get("payee")))
                .singleElement()
                .satisfies(operation ->
                        assertThat(operation.get("transferSuggestions")).isNull());

        confirm(preview, List.of(Map.of("key", out.get("key"), "pairKey", in.get("key"))));

        List<Transaction> imported = userTransactions();
        assertThat(imported).hasSize(2);
        Transaction transfer = imported.stream()
                .filter(t -> t.getType() == TransactionType.TRANSFER)
                .findFirst()
                .orElseThrow();
        assertThat(accounts.findById(transfer.getAccount().getId())
                        .orElseThrow()
                        .getName())
                .isEqualTo("Card A");
        assertThat(transfer.getAmount()).isEqualByComparingTo("1000");
        assertThat(accounts.findById(transfer.getToAccount().getId())
                        .orElseThrow()
                        .getName())
                .isEqualTo("Card B");
        assertThat(transfer.getToAmount()).isEqualByComparingTo("965");

        // Importing the same file again brings neither half back
        Map<String, Object> again = preview(PAIR_CSV);
        assertThat(operations(again))
                .filteredOn(operation -> !(Boolean) operation.get("duplicate"))
                .isEmpty();
    }

    @Test
    void suggestsAnOperationOfTheLedgerAndMergesWithIt() throws Exception {
        Account mono = accounts.save(new Account(user.getId(), "Monobank", AccountType.CARD, "UAH"));
        Transaction income = ledger(mono, TransactionType.INCOME, "1000", LocalDate.of(2026, 9, 10));
        String csv = HEADER
                + "2026-09-10,,,To Monobank,Card A,\"1000,00\",UAH,,,,\"2026-09-10 10:00:00\",\"2026-09-10 10:00:00\"\n";

        Map<String, Object> preview = preview(csv);

        Map<String, Object> out = byComment(preview, "To Monobank");
        assertThat(suggestions(out))
                .singleElement()
                .satisfies(suggestion -> assertThat(suggestion)
                        .containsEntry("transactionId", income.getId().intValue())
                        .containsEntry("accountName", "Monobank")
                        .containsEntry("exact", true));

        confirm(preview, List.of(Map.of("key", out.get("key"), "pairTransactionId", income.getId())));

        assertThat(transactions.findById(income.getId())).isEmpty();
        assertThat(userTransactions()).singleElement().satisfies(transfer -> {
            assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
            assertThat(transfer.getToAccount().getId()).isEqualTo(mono.getId());
        });
    }

    @Test
    void rejectsAPairOfTheSameKind() throws Exception {
        Map<String, Object> preview = preview(PAIR_CSV);
        Map<String, Object> out = byComment(preview, "To my card");
        Map<String, Object> shop = operations(preview).stream()
                .filter(operation -> "Shop".equals(operation.get("payee")))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(postJson(
                        "/api/v1/imports/previews/" + preview.get("id") + "/confirm",
                        Map.of("operations", List.of(Map.of("key", out.get("key"), "pairKey", shop.get("key")))),
                        auth))
                .andExpect(status().isBadRequest());

        // One income cannot be the other half of two expenses
        Map<String, Object> in = byComment(preview, "From my card");
        mockMvc.perform(postJson(
                        "/api/v1/imports/previews/" + preview.get("id") + "/confirm",
                        Map.of(
                                "operations",
                                List.of(
                                        Map.of("key", out.get("key"), "pairKey", in.get("key")),
                                        Map.of("key", shop.get("key"), "pairKey", in.get("key")))),
                        auth))
                .andExpect(status().isBadRequest());
    }

    // the feed

    @Test
    void suggestsMergesSplitsAndDismissesPairsOfTheLedger() throws Exception {
        Account mono = accounts.save(new Account(user.getId(), "Monobank", AccountType.CARD, "UAH"));
        Account privat = accounts.save(new Account(user.getId(), "PrivatBank", AccountType.CARD, "UAH"));
        LocalDate day = LocalDate.of(2026, 9, 10);
        Transaction expense = ledger(mono, TransactionType.EXPENSE, "10000", day);
        Transaction income = ledger(privat, TransactionType.INCOME, "9650", day);
        // Too far apart: 20 %
        ledger(privat, TransactionType.INCOME, "8000", day);
        String period = "/api/v1/transactions/transfer-pairs?from=2026-09-01&to=2026-09-30";

        mockMvc.perform(get(period).header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].expenseId").value(expense.getId()))
                .andExpect(jsonPath("$[0].incomeId").value(income.getId()))
                .andExpect(jsonPath("$[0].exact").value(false))
                .andExpect(jsonPath("$[0].difference").value(-0.035));

        Map<String, Long> pair = Map.of("expenseId", expense.getId(), "incomeId", income.getId());
        mockMvc.perform(postJson("/api/v1/transactions/transfer-pairs/merge", pair, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.toAmount").value(9650));
        mockMvc.perform(get(period).header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(0));

        // Split again: an expense and an income, and the pair is not suggested any more
        mockMvc.perform(post("/api/v1/transactions/" + expense.getId() + "/split-transfer")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("EXPENSE"));
        assertThat(userTransactions())
                .filteredOn(t -> t.getType() == TransactionType.INCOME)
                .extracting(Transaction::getAmount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyInAnyOrder(new BigDecimal("9650"), new BigDecimal("8000"));
        mockMvc.perform(get(period).header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDismissedPairIsNotSuggestedAgain() throws Exception {
        Account mono = accounts.save(new Account(user.getId(), "Monobank", AccountType.CARD, "UAH"));
        Account privat = accounts.save(new Account(user.getId(), "PrivatBank", AccountType.CARD, "UAH"));
        LocalDate day = LocalDate.of(2026, 9, 10);
        Transaction expense = ledger(mono, TransactionType.EXPENSE, "500", day);
        Transaction income = ledger(privat, TransactionType.INCOME, "500", day);
        String period = "/api/v1/transactions/transfer-pairs?from=2026-09-01&to=2026-09-30";
        mockMvc.perform(get(period).header("Authorization", auth))
                .andExpect(jsonPath("$[0].exact").value(true));

        mockMvc.perform(postJson(
                        "/api/v1/transactions/transfer-pairs/dismiss",
                        Map.of("expenseId", expense.getId(), "incomeId", income.getId()),
                        auth))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(period).header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(0));
        // Another user's operations cannot be merged
        String other = loginNewUser();
        mockMvc.perform(postJson(
                        "/api/v1/transactions/transfer-pairs/merge",
                        Map.of("expenseId", expense.getId(), "incomeId", income.getId()),
                        other))
                .andExpect(status().isNotFound());
    }
}
