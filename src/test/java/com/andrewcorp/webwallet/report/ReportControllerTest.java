package com.andrewcorp.webwallet.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

class ReportControllerTest extends AbstractIntegrationTest {

    private static final String PERIOD = "?from=2026-08-01&to=2026-09-30";

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;
    private Long card;
    private Long food;
    private Long groceries;
    private Long transport;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 8, 31), new BigDecimal("40")));
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 9, 8), new BigDecimal("40")));
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 9, 30), new BigDecimal("41")));

        card = account("Card", "CARD", "UAH", 1000);
        Long credit = account("Credit", "CREDIT_CARD", "UAH", -500);
        Long dollars = account("Dollars", "CASH", "USD", 100);
        food = category("Food", "EXPENSE", null);
        groceries = category("Groceries", "EXPENSE", food);
        transport = category("Transport", "EXPENSE", null);
        Long salary = category("Salary", "INCOME", null);

        transaction("INCOME", "2026-08-10", card, 10000, salary, "ACME");
        transaction("EXPENSE", "2026-08-15", card, 2000, groceries, "Silpo");
        transaction("EXPENSE", "2026-08-20", card, 500, food, "Silpo");
        transaction("EXPENSE", "2026-09-05", card, 1000, transport, "Uklon");
        transaction("EXPENSE", "2026-09-06", card, 300, null, null);
        // Paying off the credit card is neither income nor expense
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-09-07",
                                "accountId",
                                card,
                                "amount",
                                400,
                                "toAccountId",
                                credit),
                        auth),
                201);
        // 10 USD at 40 UAH
        transaction("EXPENSE", "2026-09-08", dollars, 10, groceries, null);
        // Outside of the period
        transaction("EXPENSE", "2026-10-01", card, 99, groceries, "Silpo");
    }

    private Long account(String name, String type, String currency, double openingBalance) throws Exception {
        return createAccount(auth, name, type, currency, openingBalance);
    }

    private Long category(String name, String kind, Long parentId) throws Exception {
        return createCategory(auth, name, kind, parentId);
    }

    private void transaction(String type, String date, Long account, double amount, Long categoryId, String payee)
            throws Exception {
        Map<String, Object> body =
                new HashMap<>(Map.of("type", type, "date", date, "accountId", account, "amount", amount));
        body.put("categoryId", categoryId);
        body.put("payeeName", payee);
        json(postJson("/api/v1/transactions", body, auth), 201);
    }

    private Map<String, Object> report(String path) throws Exception {
        return json(get("/api/v1/reports/" + path).header("Authorization", auth), 200);
    }

    private static BigDecimal amount(Object value) {
        return new BigDecimal(value.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void spendingByCategory() throws Exception {
        Map<String, Object> spending = report("spending" + PERIOD);

        assertThat(spending).containsEntry("currency", "UAH");
        assertThat(amount(spending.get("total"))).isEqualByComparingTo("4200");
        assertThat(amount(spending.get("uncategorized"))).isEqualByComparingTo("300");
        List<Map<String, Object>> categories = (List<Map<String, Object>>) spending.get("categories");
        assertThat(categories).extracting(c -> c.get("name")).containsExactly("Food", "Transport");
        Map<String, Object> foodRow = categories.get(0);
        // Own spending of the parent plus its subcategory, in UAH
        assertThat(amount(foodRow.get("amount"))).isEqualByComparingTo("2900");
        assertThat(amount(foodRow.get("share"))).isEqualByComparingTo("0.6905");
        Map<String, Object> groceriesRow = ((List<Map<String, Object>>) foodRow.get("children")).get(0);
        assertThat(((Number) groceriesRow.get("categoryId")).longValue()).isEqualTo(groceries);
        assertThat(amount(groceriesRow.get("amount"))).isEqualByComparingTo("2400");
    }

    @Test
    void refundReducesSpendingInsteadOfCountingAsIncome() throws Exception {
        transaction("INCOME", "2026-09-10", card, 200, groceries, "Silpo");

        Map<String, Object> spending = report("spending" + PERIOD);
        assertThat(amount(spending.get("total"))).isEqualByComparingTo("4000");
        Map<String, Object> report = report("income-expense" + PERIOD);
        assertThat(amount(report.get("income"))).isEqualByComparingTo("10000");
        assertThat(amount(report.get("expense"))).isEqualByComparingTo("4000");
    }

    @Test
    void spendingByPayee() throws Exception {
        mockMvc.perform(get("/api/v1/reports/payees" + PERIOD + "&limit=1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payees.length()").value(1))
                .andExpect(jsonPath("$.payees[0].name").value("Silpo"))
                .andExpect(jsonPath("$.payees[0].amount").value(2500))
                .andExpect(jsonPath("$.payees[0].transactions").value(2));
        mockMvc.perform(get("/api/v1/reports/payees" + PERIOD).header("Authorization", auth))
                .andExpect(jsonPath("$.payees[1].name").value("Uklon"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incomeAndExpensePerMonth() throws Exception {
        Map<String, Object> report = report("income-expense" + PERIOD);

        assertThat(amount(report.get("income"))).isEqualByComparingTo("10000");
        assertThat(amount(report.get("expense"))).isEqualByComparingTo("4200");
        assertThat(amount(report.get("savingsRate"))).isEqualByComparingTo("0.58");
        List<Map<String, Object>> months = (List<Map<String, Object>>) report.get("months");
        assertThat(months).extracting(m -> m.get("month")).containsExactly("2026-08", "2026-09");
        assertThat(amount(months.get(0).get("net"))).isEqualByComparingTo("7500");
        assertThat(amount(months.get(0).get("savingsRate"))).isEqualByComparingTo("0.75");
        assertThat(amount(months.get(1).get("expense"))).isEqualByComparingTo("1700");
        assertThat(months.get(1).get("savingsRate")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void netWorthAtTheEndOfEveryMonth() throws Exception {
        List<Map<String, Object>> months =
                (List<Map<String, Object>>) report("net-worth" + PERIOD).get("months");

        // August: card 8 500, dollars 100 × 40, credit card debt 500
        assertThat(amount(months.get(0).get("assets"))).isEqualByComparingTo("12500");
        assertThat(amount(months.get(0).get("liabilities"))).isEqualByComparingTo("-500");
        assertThat(amount(months.get(0).get("netWorth"))).isEqualByComparingTo("12000");
        // September: card 6 800, dollars 90 × 41, credit card debt 100
        assertThat(amount(months.get(1).get("assets"))).isEqualByComparingTo("10490");
        assertThat(amount(months.get(1).get("liabilities"))).isEqualByComparingTo("-100");
        assertThat(amount(months.get(1).get("netWorth"))).isEqualByComparingTo("10390");
    }

    @Test
    void defaultPeriodIsTheLastTwelveMonths() throws Exception {
        mockMvc.perform(get("/api/v1/reports/income-expense").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(12));
    }

    @Test
    void periodValidation() throws Exception {
        mockMvc.perform(get("/api/v1/reports/spending?from=2026-10-01&to=2026-09-01")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        mockMvc.perform(get("/api/v1/reports/net-worth?from=1990-01-01&to=2026-09-01")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/payees?limit=1000").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsOfAnotherUserAreEmpty() throws Exception {
        String other = loginNewUser();

        mockMvc.perform(get("/api/v1/reports/spending" + PERIOD).header("Authorization", other))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.categories.length()").value(0));
    }
}
