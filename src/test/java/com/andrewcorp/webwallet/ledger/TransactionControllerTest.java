package com.andrewcorp.webwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionControllerTest extends AbstractIntegrationTest {

    private String auth;
    private Long card;
    private Long cash;
    private Long usdAccount;
    private Long food;
    private Long groceries;
    private Long salary;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        card = account("Card", "CARD", "UAH", 1000);
        cash = account("Cash", "CASH", "UAH", 0);
        usdAccount = account("Dollars", "CASH", "USD", 0);
        food = category("Food", "EXPENSE", null);
        groceries = category("Groceries", "EXPENSE", food);
        salary = category("Salary", "INCOME", null);
    }

    private Long account(String name, String type, String currency, double openingBalance) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", currency, "openingBalance", openingBalance),
                        auth),
                201));
    }

    private Long category(String name, String kind, Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind));
        body.put("parentId", parentId);
        return id(json(postJson("/api/v1/categories", body, auth), 201));
    }

    private Map<String, Object> expense(Long account, double amount) {
        return new HashMap<>(Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", account, "amount", amount));
    }

    private double balance(Long account) throws Exception {
        return ((Number) json(get("/api/v1/accounts/" + account).header("Authorization", auth), 200)
                        .get("balance"))
                .doubleValue();
    }

    // expenses and income

    @Test
    void expenseWithCategoryPayeeAndTags() throws Exception {
        Long tag = id(json(postJson("/api/v1/tags", Map.of("name", "vacation"), auth), 201));
        Map<String, Object> body = expense(card, 250.5);
        body.put("categoryId", groceries);
        body.put("payeeName", "Silpo");
        body.put("comment", "weekly shopping");
        body.put("tagIds", List.of(tag));

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.amount").value(250.5))
                .andExpect(jsonPath("$.splits.length()").value(1))
                .andExpect(jsonPath("$.splits[0].categoryId").value(groceries))
                .andExpect(jsonPath("$.splits[0].amount").value(250.5))
                .andExpect(jsonPath("$.payee.name").value("Silpo"))
                .andExpect(jsonPath("$.tagIds[0]").value(tag))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        assertThat(balance(card)).isEqualTo(749.5);
        // The payee was created and is reused by name, ignoring case
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-02",
                                "accountId",
                                card,
                                "amount",
                                1,
                                "payeeName",
                                "SILPO"),
                        auth),
                201);
        mockMvc.perform(get("/api/v1/payees").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void uncategorizedExpense() throws Exception {
        mockMvc.perform(postJson("/api/v1/transactions", expense(cash, 10), auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splits[0].categoryId").doesNotExist());
    }

    @Test
    void splitExpense() throws Exception {
        Map<String, Object> body = expense(card, 300);
        body.put(
                "splits",
                List.of(
                        Map.of("categoryId", groceries, "amount", 200, "comment", "food"),
                        Map.of("categoryId", food, "amount", 100)));

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splits.length()").value(2))
                .andExpect(jsonPath("$.splits[0].comment").value("food"));
    }

    @Test
    void splitAmountsMustSumUp() throws Exception {
        Map<String, Object> body = expense(card, 300);
        body.put("splits", List.of(Map.of("categoryId", groceries, "amount", 200)));

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("splits"))
                .andExpect(
                        jsonPath("$.errors[0].message").value("Split amounts must sum up to the transaction amount"));
    }

    @Test
    void implausibleDatesAreRejected() throws Exception {
        Map<String, Object> body = expense(card, 100);
        body.put("date", "1950-01-01");

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
    }

    @Test
    void categoryAndSplitsAreExclusive() throws Exception {
        Map<String, Object> body = expense(card, 100);
        body.put("categoryId", food);
        body.put("splits", List.of(Map.of("categoryId", groceries, "amount", 100)));

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    @Test
    void categoryKindMustMatchTransactionTypeExceptForRefunds() throws Exception {
        Map<String, Object> body = expense(card, 100);
        body.put("categoryId", salary);
        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));

        Map<String, Object> income = new HashMap<>(Map.of(
                "type",
                "INCOME",
                "date",
                "2026-10-01",
                "accountId",
                card,
                "amount",
                5000,
                "splits",
                List.of(Map.of("categoryId", groceries, "amount", 5000))));
        // An income in an expense category is a refund
        mockMvc.perform(postJson("/api/v1/transactions", income, auth)).andExpect(status().isCreated());
    }

    @Test
    void incomeIncreasesBalance() throws Exception {
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "INCOME",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                5000,
                                "categoryId",
                                salary),
                        auth),
                201);

        assertThat(balance(card)).isEqualTo(6000);
    }

    @Test
    void foreignCurrencyPurchaseKeepsOriginalAmount() throws Exception {
        Map<String, Object> body = expense(card, 904.98);
        body.put("originalAmount", 20);
        body.put("originalCurrency", "USD");

        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalAmount").value(20))
                .andExpect(jsonPath("$.originalCurrency").value("USD"));

        body.remove("originalCurrency");
        mockMvc.perform(postJson("/api/v1/transactions", body, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("originalCurrency"));
    }

    // transfers

    @Test
    void transferInSameCurrencyDefaultsToSameAmount() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                300,
                                "toAccountId",
                                cash),
                        auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAccountId").value(cash))
                .andExpect(jsonPath("$.toAmount").value(300))
                .andExpect(jsonPath("$.toCurrency").value("UAH"))
                .andExpect(jsonPath("$.splits.length()").value(0));

        assertThat(balance(card)).isEqualTo(700);
        assertThat(balance(cash)).isEqualTo(300);
    }

    @Test
    void transferWithFee() throws Exception {
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                1000,
                                "toAccountId",
                                cash,
                                "toAmount",
                                960),
                        auth),
                201);

        assertThat(balance(card)).isZero();
        assertThat(balance(cash)).isEqualTo(960);
    }

    @Test
    void crossCurrencyTransferRequiresTargetAmount() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                449,
                                "toAccountId",
                                usdAccount),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAmount"));

        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                449,
                                "toAccountId",
                                usdAccount,
                                "toAmount",
                                10),
                        auth),
                201);
        assertThat(balance(usdAccount)).isEqualTo(10);
    }

    @Test
    void transferValidation() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of("type", "TRANSFER", "date", "2026-10-01", "accountId", card, "amount", 1),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAccountId"));
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                1,
                                "toAccountId",
                                card),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAccountId"));
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                1,
                                "toAccountId",
                                cash,
                                "categoryId",
                                food),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("splits"));
        mockMvc.perform(postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                1,
                                "toAccountId",
                                cash),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAccountId"));
    }

    @Test
    void foreignReferencesAreRejected() throws Exception {
        String otherAuth = loginNewUser();
        Long foreignAccount = id(json(
                postJson("/api/v1/accounts", Map.of("name", "x", "type", "CASH", "currency", "UAH"), otherAuth), 201));
        Long foreignTag = id(json(postJson("/api/v1/tags", Map.of("name", "x"), otherAuth), 201));
        Long foreignPayee = id(json(postJson("/api/v1/payees", Map.of("name", "x"), otherAuth), 201));

        mockMvc.perform(postJson("/api/v1/transactions", expense(foreignAccount, 1), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"));
        Map<String, Object> withTag = expense(card, 1);
        withTag.put("tagIds", List.of(foreignTag));
        mockMvc.perform(postJson("/api/v1/transactions", withTag, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("tagIds"));
        Map<String, Object> withPayee = expense(card, 1);
        withPayee.put("payeeId", foreignPayee);
        mockMvc.perform(postJson("/api/v1/transactions", withPayee, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payeeId"));
        Map<String, Object> withCategory = expense(card, 1);
        withCategory.put("categoryId", 999999);
        mockMvc.perform(postJson("/api/v1/transactions", withCategory, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    @Test
    void bodyValidation() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/transactions", Map.of("type", "EXPENSE", "accountId", card, "amount", -5), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    // update, delete, read

    @Test
    void updateTurnsExpenseIntoTransfer() throws Exception {
        Map<String, Object> body = expense(card, 100);
        body.put("categoryId", food);
        Long id = id(json(postJson("/api/v1/transactions", body, auth), 201));

        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + id,
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-03",
                                "accountId",
                                card,
                                "amount",
                                100,
                                "toAccountId",
                                cash),
                        auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.date").value("2026-10-03"))
                .andExpect(jsonPath("$.splits.length()").value(0));
        assertThat(balance(cash)).isEqualTo(100);

        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + id,
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-03",
                                "accountId",
                                card,
                                "amount",
                                100,
                                "categoryId",
                                food),
                        auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toAccountId").doesNotExist())
                .andExpect(jsonPath("$.splits[0].categoryId").value(food));
        assertThat(balance(cash)).isZero();
    }

    @Test
    void getAndDelete() throws Exception {
        Long id = id(json(postJson("/api/v1/transactions", expense(card, 100), auth), 201));

        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", loginNewUser()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/transactions/" + id).header("Authorization", auth))
                .andExpect(status().isNoContent());
        assertThat(balance(card)).isEqualTo(1000);
        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWithFiltersAndPagination() throws Exception {
        Long tag = id(json(postJson("/api/v1/tags", Map.of("name", "trip"), auth), 201));
        Map<String, Object> groceriesExpense = expense(card, 10);
        groceriesExpense.put("date", "2026-09-15");
        groceriesExpense.put("categoryId", groceries);
        groceriesExpense.put("tagIds", List.of(tag));
        Long payee = id(json(postJson("/api/v1/payees", Map.of("name", "Silpo"), auth), 201));
        groceriesExpense.put("payeeId", payee);
        Long first = id(json(postJson("/api/v1/transactions", groceriesExpense, auth), 201));
        Long second = id(json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                card,
                                "amount",
                                5,
                                "toAccountId",
                                cash),
                        auth),
                201));
        Long third = id(json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "INCOME",
                                "date",
                                "2026-10-05",
                                "accountId",
                                cash,
                                "amount",
                                7,
                                "categoryId",
                                salary),
                        auth),
                201));
        // Another user's transactions are never listed
        String otherAuth = loginNewUser();
        Long otherAccount = id(json(
                postJson("/api/v1/accounts", Map.of("name", "x", "type", "CASH", "currency", "UAH"), otherAuth), 201));
        json(postJson("/api/v1/transactions", expense(otherAccount, 1), otherAuth), 201);

        assertThat(ids("")).containsExactly(third, second, first);
        assertThat(ids("from=2026-10-01&to=2026-10-03")).containsExactly(second);
        assertThat(ids("accountId=" + cash)).containsExactly(third, second);
        assertThat(ids("categoryId=" + food)).containsExactly(first);
        assertThat(ids("type=INCOME")).containsExactly(third);
        assertThat(ids("tagId=" + tag)).containsExactly(first);
        assertThat(ids("payeeId=" + payee)).containsExactly(first);

        mockMvc.perform(get("/api/v1/transactions?page=1&size=2").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/transactions?size=500").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @SuppressWarnings("unchecked")
    private List<Long> ids(String query) throws Exception {
        Map<String, Object> page = json(get("/api/v1/transactions?" + query).header("Authorization", auth), 200);
        return ((List<Map<String, Object>>) page.get("items"))
                .stream().map(item -> id(item)).toList();
    }
}
