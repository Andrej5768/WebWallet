package com.andrewcorp.webwallet.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BudgetControllerTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;
    private Long card;
    private Long cash;
    private Long deposit;
    private Long food;
    private Long groceries;
    private Long transport;
    private Long salary;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        card = account("Card", "CARD", "UAH", 1000);
        cash = account("Cash", "CASH", "UAH", 0);
        deposit = account("Deposit", "DEPOSIT", "UAH", 5000);
        food = category("Food", "EXPENSE", null);
        groceries = category("Groceries", "EXPENSE", food);
        transport = category("Transport", "EXPENSE", null);
        salary = category("Salary", "INCOME", null);
    }

    private Long account(String name, String type, String currency, double openingBalance) throws Exception {
        return createAccount(auth, name, type, currency, openingBalance);
    }

    private Long category(String name, String kind, Long parentId) throws Exception {
        return createCategory(auth, name, kind, parentId);
    }

    private void transaction(String type, String date, Long account, double amount, Long categoryId) throws Exception {
        Map<String, Object> body =
                new HashMap<>(Map.of("type", type, "date", date, "accountId", account, "amount", amount));
        body.put("categoryId", categoryId);
        json(postJson("/api/v1/transactions", body, auth), 201);
    }

    private void transfer(String date, Long from, Long to, double amount) throws Exception {
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                date,
                                "accountId",
                                from,
                                "amount",
                                amount,
                                "toAccountId",
                                to),
                        auth),
                201);
    }

    private Map<String, Object> month(String month) throws Exception {
        return json(get("/api/v1/budget/" + month).header("Authorization", auth), 200);
    }

    private void assign(String month, Long category, double amount) throws Exception {
        mockMvc.perform(putJson(
                        "/api/v1/budget/" + month + "/categories/" + category, Map.of("assigned", amount), auth))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> month, Long categoryId) {
        for (Map<String, Object> parent : (List<Map<String, Object>>) month.get("categories")) {
            if (((Number) parent.get("categoryId")).longValue() == categoryId) {
                return parent;
            }
            for (Map<String, Object> child : (List<Map<String, Object>>) parent.get("children")) {
                if (((Number) child.get("categoryId")).longValue() == categoryId) {
                    return child;
                }
            }
        }
        throw new AssertionError("No row for category " + categoryId);
    }

    private static BigDecimal amount(Map<String, Object> json, String field) {
        return new BigDecimal(json.get(field).toString());
    }

    @Test
    void openingBalancesOfBudgetAccountsAreReadyToAssign() throws Exception {
        Map<String, Object> october = month("2026-10");

        assertThat(october).containsEntry("currency", "UAH").containsEntry("month", "2026-10");
        // The deposit is outside the budget
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("1000");
        assertThat(amount(row(october, groceries), "available")).isEqualByComparingTo("0");
    }

    @Test
    void assignSpendAndCarryOver() throws Exception {
        transaction("INCOME", "2026-10-01", card, 5000, salary);
        assign("2026-10", groceries, 3000);
        transaction("EXPENSE", "2026-10-05", card, 1000, groceries);

        Map<String, Object> october = month("2026-10");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("3000");
        Map<String, Object> groceriesRow = row(october, groceries);
        assertThat(amount(groceriesRow, "assigned")).isEqualByComparingTo("3000");
        assertThat(amount(groceriesRow, "activity")).isEqualByComparingTo("-1000");
        assertThat(amount(groceriesRow, "available")).isEqualByComparingTo("2000");
        // A parent includes its subcategories
        assertThat(amount(row(october, food), "available")).isEqualByComparingTo("2000");
        assertThat(amount(october, "assigned")).isEqualByComparingTo("3000");

        // The rest moves to the next month
        Map<String, Object> november = month("2026-11");
        assertThat(amount(row(november, groceries), "assigned")).isEqualByComparingTo("0");
        assertThat(amount(row(november, groceries), "available")).isEqualByComparingTo("2000");
        assertThat(amount(november, "readyToAssign")).isEqualByComparingTo("3000");
    }

    @Test
    void overspendingIsTakenFromTheNextMonth() throws Exception {
        transaction("EXPENSE", "2026-10-10", card, 400, transport);

        Map<String, Object> october = month("2026-10");
        assertThat(amount(row(october, transport), "available")).isEqualByComparingTo("-400");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("1000");

        Map<String, Object> november = month("2026-11");
        assertThat(amount(row(november, transport), "available")).isEqualByComparingTo("0");
        assertThat(amount(november, "readyToAssign")).isEqualByComparingTo("600");
        // Also when there is nothing in between
        assertThat(amount(month("2027-02"), "readyToAssign")).isEqualByComparingTo("600");
    }

    @Test
    void uncategorizedSpendingAndTransfersAcrossTheBudget() throws Exception {
        transaction("EXPENSE", "2026-10-02", card, 200, null);
        // Within the budget: no change
        transfer("2026-10-03", card, cash, 100);
        // Out of and into the budget
        transfer("2026-10-04", card, deposit, 500);
        transfer("2026-10-05", deposit, cash, 50);
        // Spending outside the budget is not tracked by the envelopes
        transaction("EXPENSE", "2026-10-06", deposit, 300, groceries);

        Map<String, Object> october = month("2026-10");

        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("350");
        assertThat(amount(row(october, groceries), "activity")).isEqualByComparingTo("0");
    }

    @Test
    void foreignCurrencyIsConvertedAtTheRateOfTheDay() throws Exception {
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 10, 7), new BigDecimal("41.5")));
        Long dollars = account("Dollars", "CASH", "USD", 0);
        transaction("EXPENSE", "2026-10-07", dollars, 10, groceries);

        assertThat(amount(row(month("2026-10"), groceries), "activity")).isEqualByComparingTo("-415");
    }

    @Test
    void assignmentRules() throws Exception {
        assign("2026-10", groceries, 100);
        assign("2026-10", groceries, 0);
        assertThat(amount(row(month("2026-10"), groceries), "assigned")).isEqualByComparingTo("0");
        // Assigning 0 to an empty envelope does nothing
        assign("2026-10", transport, 0);

        mockMvc.perform(putJson("/api/v1/budget/2026-10/categories/" + salary, Map.of("assigned", 1), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        // A negative amount takes money out of the envelope, back to ready to assign
        mockMvc.perform(putJson("/api/v1/budget/2026-10/categories/" + groceries, Map.of("assigned", -1), auth))
                .andExpect(status().isOk());
        assertThat(amount(row(month("2026-10"), groceries), "assigned")).isEqualByComparingTo("-1");
        mockMvc.perform(putJson(
                        "/api/v1/budget/2026-10/categories/" + groceries, Map.of("assigned", 1), loginNewUser()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/budget/october").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parentRowsHaveTheirOwnValuesNextToTheTotals() throws Exception {
        assign("2026-10", food, 500);
        assign("2026-10", groceries, 300);
        transaction("EXPENSE", "2026-10-05", card, 100, food);
        transaction("EXPENSE", "2026-10-06", card, 50, groceries);

        Map<String, Object> foodRow = row(month("2026-10"), food);
        assertThat(amount(foodRow, "assigned")).isEqualByComparingTo("800");
        assertThat(amount(foodRow, "activity")).isEqualByComparingTo("-150");
        assertThat(amount(foodRow, "available")).isEqualByComparingTo("650");
        assertThat(amount(foodRow, "ownAssigned")).isEqualByComparingTo("500");
        assertThat(amount(foodRow, "ownActivity")).isEqualByComparingTo("-100");
        assertThat(amount(foodRow, "ownAvailable")).isEqualByComparingTo("400");
        Map<String, Object> groceriesRow = row(month("2026-10"), groceries);
        assertThat(amount(groceriesRow, "ownAssigned")).isEqualByComparingTo("300");
        assertThat(amount(groceriesRow, "ownAvailable")).isEqualByComparingTo("250");
    }

    @Test
    void moveMoneyBetweenEnvelopes() throws Exception {
        assign("2026-10", groceries, 300);

        Map<String, Object> october = json(
                postJson(
                        "/api/v1/budget/2026-10/move",
                        Map.of("fromCategoryId", groceries, "toCategoryId", food, "amount", 120.5),
                        auth),
                200);

        assertThat(october).containsEntry("month", "2026-10");
        assertThat(amount(row(october, groceries), "assigned")).isEqualByComparingTo("179.5");
        assertThat(amount(row(october, food), "ownAssigned")).isEqualByComparingTo("120.5");
        // The total stays the same
        assertThat(amount(row(october, food), "assigned")).isEqualByComparingTo("300");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("700");

        // Moving everything out removes the assignment; moving more takes the envelope below zero
        json(
                postJson(
                        "/api/v1/budget/2026-10/move",
                        Map.of("fromCategoryId", groceries, "toCategoryId", transport, "amount", 200),
                        auth),
                200);
        Map<String, Object> after = month("2026-10");
        assertThat(amount(row(after, groceries), "assigned")).isEqualByComparingTo("-20.5");
        assertThat(amount(row(after, transport), "assigned")).isEqualByComparingTo("200");
    }

    @Test
    void moveRules() throws Exception {
        assign("2026-10", groceries, 300);
        String url = "/api/v1/budget/2026-10/move";

        mockMvc.perform(postJson(
                        url, Map.of("fromCategoryId", groceries, "toCategoryId", groceries, "amount", 1), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toCategoryId"));
        mockMvc.perform(postJson(url, Map.of("fromCategoryId", groceries, "toCategoryId", salary, "amount", 1), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toCategoryId"));
        mockMvc.perform(postJson(url, Map.of("fromCategoryId", groceries, "toCategoryId", food, "amount", 0), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        mockMvc.perform(postJson(url, Map.of("fromCategoryId", groceries, "toCategoryId", food, "amount", -5), auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postJson(url, Map.of("toCategoryId", food, "amount", 5), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromCategoryId"));
        // Categories of another user are not found, and nothing changed
        String other = loginNewUser();
        mockMvc.perform(postJson(url, Map.of("fromCategoryId", groceries, "toCategoryId", food, "amount", 1), other))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromCategoryId"));
        assertThat(amount(row(month("2026-10"), groceries), "assigned")).isEqualByComparingTo("300");
        assertThat(amount(row(month("2026-10"), food), "ownAssigned")).isEqualByComparingTo("0");
    }

    @Test
    void copyThePreviousMonthToEmptyEnvelopes() throws Exception {
        assign("2026-09", food, 50);
        assign("2026-09", groceries, 300);
        assign("2026-09", transport, 100);
        assign("2026-10", transport, 70);
        Long old = category("Old", "EXPENSE", null);
        assign("2026-09", old, 10);
        mockMvc.perform(putJson(
                        "/api/v1/categories/" + old, Map.of("name", "Old", "kind", "EXPENSE", "archived", true), auth))
                .andExpect(status().isOk());

        Map<String, Object> october =
                json(post("/api/v1/budget/2026-10/copy-previous").header("Authorization", auth), 200);

        assertThat(october).containsEntry("month", "2026-10");
        assertThat(amount(row(october, groceries), "assigned")).isEqualByComparingTo("300");
        assertThat(amount(row(october, food), "ownAssigned")).isEqualByComparingTo("50");
        // Already assigned this month: kept
        assertThat(amount(row(october, transport), "assigned")).isEqualByComparingTo("70");
        assertThat(amount(row(october, old), "assigned")).isEqualByComparingTo("0");
        assertThat(amount(october, "assigned")).isEqualByComparingTo("420");

        // Nothing to copy from an empty month
        Map<String, Object> december =
                json(post("/api/v1/budget/2026-12/copy-previous").header("Authorization", auth), 200);
        assertThat(amount(december, "assigned")).isEqualByComparingTo("0");
    }

    @Test
    void archivedCategoriesWithoutMoneyAreHidden() throws Exception {
        Long old = category("Old", "EXPENSE", null);
        mockMvc.perform(putJson(
                        "/api/v1/categories/" + old, Map.of("name", "Old", "kind", "EXPENSE", "archived", true), auth))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/budget/2026-10").header("Authorization", auth))
                .andExpect(jsonPath("$.categories[*].name", contains("Food", "Transport")));
    }
}
