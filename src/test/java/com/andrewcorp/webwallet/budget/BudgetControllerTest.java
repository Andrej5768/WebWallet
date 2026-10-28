package com.andrewcorp.webwallet.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
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

    @SuppressWarnings("unchecked")
    private static List<Object> names(Map<String, Object> month) {
        return ((List<Map<String, Object>>) month.get("categories"))
                .stream().map(row -> row.get("name")).toList();
    }

    private static BigDecimal amount(Map<String, Object> json, String field) {
        return new BigDecimal(json.get(field).toString());
    }

    private void carryOver(Long categoryId, String name, Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "EXPENSE", "carryOver", true));
        body.put("parentId", parentId);
        Map<String, Object> category = json(putJson("/api/v1/categories/" + categoryId, body, auth), 200);
        assertThat(category).containsEntry("carryOver", true);
    }

    @Test
    void theOpeningBalanceIsNotIncomeOfTheMonth() throws Exception {
        Map<String, Object> october = month("2026-10");

        assertThat(october).containsEntry("currency", "UAH").containsEntry("month", "2026-10");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("0");
        assertThat(amount(october, "inflow")).isEqualByComparingTo("0");
        assertThat(amount(row(october, groceries), "available")).isEqualByComparingTo("0");
    }

    @Test
    void thePlanOfAMonthIsComparedWithItsIncomeAndExpiresWithIt() throws Exception {
        transaction("INCOME", "2026-10-01", card, 5000, salary);
        assign("2026-10", groceries, 3000);
        transaction("EXPENSE", "2026-10-05", card, 1000, groceries);
        transaction("EXPENSE", "2026-10-10", card, 400, transport);

        Map<String, Object> october = month("2026-10");
        assertThat(amount(october, "inflow")).isEqualByComparingTo("5000");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("2000");
        Map<String, Object> groceriesRow = row(october, groceries);
        assertThat(amount(groceriesRow, "assigned")).isEqualByComparingTo("3000");
        assertThat(amount(groceriesRow, "activity")).isEqualByComparingTo("-1000");
        assertThat(amount(groceriesRow, "available")).isEqualByComparingTo("2000");
        // A parent includes its subcategories
        assertThat(amount(row(october, food), "available")).isEqualByComparingTo("2000");
        assertThat(amount(october, "assigned")).isEqualByComparingTo("3000");
        // Overspending of the month stays in the month
        assertThat(amount(row(october, transport), "available")).isEqualByComparingTo("-400");

        // Nothing moves to the next month, neither the rest nor the overspending
        Map<String, Object> november = month("2026-11");
        assertThat(amount(row(november, groceries), "assigned")).isEqualByComparingTo("0");
        assertThat(amount(row(november, groceries), "available")).isEqualByComparingTo("0");
        assertThat(amount(row(november, transport), "available")).isEqualByComparingTo("0");
        assertThat(amount(november, "inflow")).isEqualByComparingTo("0");
        assertThat(amount(november, "readyToAssign")).isEqualByComparingTo("0");
    }

    @Test
    void aCategoryWithCarryOverIsAnEnvelope() throws Exception {
        carryOver(groceries, "Groceries", food);
        carryOver(transport, "Transport", null);
        transaction("INCOME", "2026-10-01", card, 5000, salary);
        assign("2026-10", groceries, 3000);
        transaction("EXPENSE", "2026-10-05", card, 1000, groceries);
        transaction("EXPENSE", "2026-10-10", card, 400, transport);

        Map<String, Object> october = month("2026-10");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("2000");
        assertThat(amount(row(october, transport), "available")).isEqualByComparingTo("-400");

        // The rest moves to the next month; the overspending is taken from the free money of the next month, once
        Map<String, Object> november = month("2026-11");
        assertThat(amount(row(november, groceries), "assigned")).isEqualByComparingTo("0");
        assertThat(amount(row(november, groceries), "available")).isEqualByComparingTo("2000");
        assertThat(amount(row(november, transport), "available")).isEqualByComparingTo("0");
        assertThat(amount(november, "readyToAssign")).isEqualByComparingTo("-400");
        Map<String, Object> february = month("2027-02");
        assertThat(amount(row(february, groceries), "available")).isEqualByComparingTo("2000");
        assertThat(amount(february, "readyToAssign")).isEqualByComparingTo("0");

        // Only expense categories carry over
        mockMvc.perform(putJson(
                        "/api/v1/categories/" + salary,
                        Map.of("name", "Salary", "kind", "INCOME", "carryOver", true),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("carryOver"));
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

        // Net transfers out of the budget are a negative inflow; the uncategorized spending is taken from it
        assertThat(amount(october, "inflow")).isEqualByComparingTo("-450");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("-650");
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
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("-300");

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
        // The archived category got nothing and, without carry-over, has no money to show
        assertThat(names(october)).doesNotContain("Old");
        assertThat(amount(october, "assigned")).isEqualByComparingTo("420");

        // Nothing to copy from an empty month
        Map<String, Object> december =
                json(post("/api/v1/budget/2026-12/copy-previous").header("Authorization", auth), 200);
        assertThat(amount(december, "assigned")).isEqualByComparingTo("0");
    }

    @Test
    void incomeIsPlannedAndComparedWithWhatWasReceived() throws Exception {
        Long bonus = category("Bonus", "INCOME", salary);
        Long gifts = category("Gifts", "INCOME", null);
        assign("2026-10", salary, 1000);
        assign("2026-10", bonus, 200);
        assign("2026-10", groceries, 300);
        transaction("INCOME", "2026-10-05", card, 900, salary);
        transaction("INCOME", "2026-10-06", card, 250, bonus);
        transaction("INCOME", "2026-10-07", card, 40, null);
        // A refund in an expense category lowers its spending and is not income of a category
        transaction("INCOME", "2026-10-08", card, 10, groceries);

        Map<String, Object> october = month("2026-10");

        assertThat(amount(october, "plannedIncome")).isEqualByComparingTo("1200");
        assertThat(amount(october, "income")).isEqualByComparingTo("1190");
        assertThat(amount(october, "uncategorizedIncome")).isEqualByComparingTo("40");
        Map<String, Object> salaryRow = incomeRow(october, salary);
        assertThat(amount(salaryRow, "planned")).isEqualByComparingTo("1200");
        assertThat(amount(salaryRow, "received")).isEqualByComparingTo("1150");
        assertThat(amount(salaryRow, "ownPlanned")).isEqualByComparingTo("1000");
        assertThat(amount(salaryRow, "ownReceived")).isEqualByComparingTo("900");
        assertThat(amount(incomeRow(october, bonus), "received")).isEqualByComparingTo("250");
        assertThat(amount(incomeRow(october, gifts), "planned")).isEqualByComparingTo("0");
        // The expected income does not change the free money: income received minus the plan of the expenses
        assertThat(amount(october, "assigned")).isEqualByComparingTo("300");
        assertThat(amount(october, "readyToAssign")).isEqualByComparingTo("890");
        assertThat(names(october)).doesNotContain("Salary");
        // The expected income of another month is not this month's
        assign("2026-11", salary, 5000);
        assertThat(amount(month("2026-10"), "plannedIncome")).isEqualByComparingTo("1200");
        assertThat(amount(month("2026-11"), "income")).isEqualByComparingTo("0");
    }

    @Test
    void severalPlansAreSavedTogether() throws Exception {
        assign("2026-10", transport, 70);
        Map<String, Object> october = json(
                putJson(
                        "/api/v1/budget/2026-10/categories",
                        Map.of(
                                "plans",
                                List.of(
                                        Map.of("categoryId", groceries, "assigned", 300),
                                        Map.of("categoryId", salary, "assigned", 1000),
                                        Map.of("categoryId", transport, "assigned", 0))),
                        auth),
                200);

        assertThat(amount(row(october, groceries), "assigned")).isEqualByComparingTo("300");
        assertThat(amount(row(october, transport), "assigned")).isEqualByComparingTo("0");
        assertThat(amount(october, "plannedIncome")).isEqualByComparingTo("1000");

        // A repeated or foreign category changes nothing
        mockMvc.perform(putJson(
                        "/api/v1/budget/2026-10/categories",
                        Map.of(
                                "plans",
                                List.of(
                                        Map.of("categoryId", groceries, "assigned", 1),
                                        Map.of("categoryId", groceries, "assigned", 2))),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plans[1].categoryId"));
        Long foreign = createCategory(loginNewUser(), "Other", "EXPENSE", null);
        mockMvc.perform(putJson(
                        "/api/v1/budget/2026-10/categories",
                        Map.of(
                                "plans",
                                List.of(
                                        Map.of("categoryId", groceries, "assigned", 1),
                                        Map.of("categoryId", foreign, "assigned", 2))),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plans[1].categoryId"));
        mockMvc.perform(putJson("/api/v1/budget/2026-10/categories", Map.of("plans", List.of()), auth))
                .andExpect(status().isBadRequest());
        assertThat(amount(row(month("2026-10"), groceries), "assigned")).isEqualByComparingTo("300");
    }

    @Test
    void copyThePreviousMonthCopiesTheExpectedIncomeToo() throws Exception {
        assign("2026-09", salary, 1000);

        Map<String, Object> october =
                json(post("/api/v1/budget/2026-10/copy-previous").header("Authorization", auth), 200);

        assertThat(amount(october, "plannedIncome")).isEqualByComparingTo("1000");
    }

    @Test
    void thePlanOfSeveralMonthsWithTheAverageOfTheLastThree() throws Exception {
        TestClock.INSTANCE.set(Instant.parse("2026-10-10T09:00:00Z"));
        Long bonus = category("Bonus", "INCOME", salary);
        assign("2026-08", groceries, 300);
        assign("2026-09", salary, 1000);
        assign("2026-11", food, 50);
        assign("2026-11", groceries, 400);
        transaction("EXPENSE", "2026-07-03", card, 90, groceries);
        transaction("EXPENSE", "2026-08-03", card, 120, groceries);
        transaction("INCOME", "2026-08-04", card, 20, groceries);
        transaction("EXPENSE", "2026-08-05", card, 30, food);
        transaction("EXPENSE", "2026-09-05", card, 60, null);
        transaction("INCOME", "2026-09-06", card, 1100, salary);
        transaction("INCOME", "2026-09-07", card, 50, bonus);
        transaction("INCOME", "2026-09-08", card, 5, null);
        transaction("EXPENSE", "2026-10-02", card, 45, groceries);
        // Outside the budget: does not count
        transaction("EXPENSE", "2026-09-09", deposit, 999, groceries);

        Map<String, Object> plan = json(
                get("/api/v1/budget")
                        .param("from", "2026-08")
                        .param("to", "2026-11")
                        .header("Authorization", auth),
                200);

        assertThat(plan).containsEntry("currency", "UAH");
        assertThat(plan.get("months")).isEqualTo(List.of("2026-08", "2026-09", "2026-10", "2026-11"));
        assertThat(plan.get("averageMonths")).isEqualTo(List.of("2026-07", "2026-08", "2026-09"));

        Map<String, Object> foodRow = planRow(plan, "expenses", food);
        // August: groceries 120 spent minus a 20 refund, plus 30 on food itself
        assertThat(amount(cell(foodRow, 0), "actual")).isEqualByComparingTo("130");
        assertThat(amount(cell(foodRow, 0), "ownActual")).isEqualByComparingTo("30");
        assertThat(amount(cell(foodRow, 0), "plan")).isEqualByComparingTo("300");
        assertThat(amount(cell(foodRow, 0), "ownPlan")).isEqualByComparingTo("0");
        assertThat(amount(cell(foodRow, 3), "plan")).isEqualByComparingTo("450");
        // Groceries over July to September: 90 + 100 + 0 = 190, a third of it
        Map<String, Object> groceriesRow = planRow(plan, "expenses", groceries);
        assertThat(amount(groceriesRow, "average")).isEqualByComparingTo("63.33");
        assertThat(amount(foodRow, "ownAverage")).isEqualByComparingTo("10");
        assertThat(amount(foodRow, "average")).isEqualByComparingTo("73.33");
        assertThat(amount(cell(groceriesRow, 2), "actual")).isEqualByComparingTo("45");

        Map<String, Object> salaryRow = planRow(plan, "income", salary);
        assertThat(amount(cell(salaryRow, 1), "plan")).isEqualByComparingTo("1000");
        assertThat(amount(cell(salaryRow, 1), "actual")).isEqualByComparingTo("1150");
        assertThat(amount(cell(salaryRow, 1), "ownActual")).isEqualByComparingTo("1100");
        assertThat(amount(salaryRow, "average")).isEqualByComparingTo("383.33");

        Map<String, Object> september = totals(plan, 1);
        assertThat(september).containsEntry("month", "2026-09");
        assertThat(amount(september, "plannedIncome")).isEqualByComparingTo("1000");
        assertThat(amount(september, "income")).isEqualByComparingTo("1155");
        assertThat(amount(september, "uncategorizedIncome")).isEqualByComparingTo("5");
        assertThat(amount(september, "expenses")).isEqualByComparingTo("60");
        assertThat(amount(september, "uncategorizedExpenses")).isEqualByComparingTo("60");
        assertThat(amount(totals(plan, 0), "plannedExpenses")).isEqualByComparingTo("300");
    }

    @Test
    void thePlanRangeIsLimited() throws Exception {
        mockMvc.perform(get("/api/v1/budget")
                        .param("from", "2026-10")
                        .param("to", "2026-09")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        mockMvc.perform(get("/api/v1/budget")
                        .param("from", "2025-01")
                        .param("to", "2028-01")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/budget")
                        .param("from", "2025-01")
                        .param("to", "2027-12")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(36));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> incomeRow(Map<String, Object> month, Long categoryId) {
        for (Map<String, Object> parent : (List<Map<String, Object>>) month.get("incomeCategories")) {
            if (((Number) parent.get("categoryId")).longValue() == categoryId) {
                return parent;
            }
            for (Map<String, Object> child : (List<Map<String, Object>>) parent.get("children")) {
                if (((Number) child.get("categoryId")).longValue() == categoryId) {
                    return child;
                }
            }
        }
        throw new AssertionError("No income row for category " + categoryId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> planRow(Map<String, Object> plan, String section, Long categoryId) {
        for (Map<String, Object> parent : (List<Map<String, Object>>) plan.get(section)) {
            if (((Number) parent.get("categoryId")).longValue() == categoryId) {
                return parent;
            }
            for (Map<String, Object> child : (List<Map<String, Object>>) parent.get("children")) {
                if (((Number) child.get("categoryId")).longValue() == categoryId) {
                    return child;
                }
            }
        }
        throw new AssertionError("No plan row for category " + categoryId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(Map<String, Object> row, int index) {
        return ((List<Map<String, Object>>) row.get("months")).get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> plan, int index) {
        return ((List<Map<String, Object>>) plan.get("totals")).get(index);
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
