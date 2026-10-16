package com.andrewcorp.webwallet.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class AnalyticsControllerTest extends AbstractIntegrationTest {

    private String auth;
    private Long card;
    private Long deposit;
    private Long food;
    private Long groceries;
    private Long fun;
    private Long transport;
    private Long salary;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        // 10 October 2026 in Kyiv: 10 of the 31 days of the month have passed
        TestClock.INSTANCE.set(Instant.parse("2026-10-10T09:00:00Z"));
        card = account("Card", "CARD");
        deposit = account("Deposit", "DEPOSIT");
        food = category("Food", "EXPENSE", null, "NEEDS");
        // Inherits NEEDS from its parent
        groceries = category("Groceries", "EXPENSE", food, null);
        fun = category("Fun", "EXPENSE", null, "WANTS");
        transport = category("Transport", "EXPENSE", null, null);
        salary = category("Salary", "INCOME", null, null);

        transaction("EXPENSE", "2026-07-05", card, 1000, groceries);
        transaction("INCOME", "2026-08-01", card, 10000, salary);
        transaction("EXPENSE", "2026-08-05", card, 1000, groceries);

        transaction("INCOME", "2026-09-01", card, 20000, salary);
        transaction("EXPENSE", "2026-09-05", card, 3000, groceries);
        transaction("EXPENSE", "2026-09-10", card, 1000, fun);
        transaction("EXPENSE", "2026-09-12", card, 500, transport);
        transfer("2026-09-20", card, deposit, 4000);
        // A refund reduces the spending on groceries
        transaction("INCOME", "2026-09-25", card, 200, groceries);

        transaction("INCOME", "2026-10-01", card, 20000, salary);
        transaction("EXPENSE", "2026-10-03", card, 2000, groceries);
        transaction("EXPENSE", "2026-10-05", card, 500, fun);
        transfer("2026-10-06", deposit, card, 1000);
    }

    private Long account(String name, String type) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", "UAH", "openingBalance", 0),
                        auth),
                201));
    }

    private Long category(String name, String kind, Long parentId, String necessity) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind));
        body.put("parentId", parentId);
        if (necessity != null) {
            body.put("necessity", necessity);
        }
        return id(json(postJson("/api/v1/categories", body, auth), 201));
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

    private void assign(String month, Long category, double amount) throws Exception {
        json(putJson("/api/v1/budget/" + month + "/categories/" + category, Map.of("assigned", amount), auth), 200);
    }

    private Map<String, Object> analytics(String path) throws Exception {
        return json(get("/api/v1/analytics/" + path).header("Authorization", auth), 200);
    }

    private void invalid(String path) throws Exception {
        json(get("/api/v1/analytics/" + path).header("Authorization", auth), 400);
    }

    private static BigDecimal amount(Map<String, Object> json, String field) {
        return new BigDecimal(json.get(field).toString());
    }

    private static List<Map<String, Object>> list(Map<String, Object> json, String field) {
        return (List<Map<String, Object>>) json.get(field);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, Long categoryId) {
        return rows.stream()
                .filter(row -> categoryId.equals(((Number) row.get("categoryId")).longValue()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void planVsActualWithForecastOfTheCurrentMonth() throws Exception {
        assign("2026-10", groceries, 3000);
        assign("2026-10", fun, 1000);

        Map<String, Object> plan = analytics("plan/2026-10");

        assertThat(plan).containsEntry("daysElapsed", 10).containsEntry("daysInMonth", 31);
        assertThat(amount(plan, "planned")).isEqualByComparingTo("4000");
        assertThat(amount(plan, "actual")).isEqualByComparingTo("2500");
        assertThat(amount(plan, "difference")).isEqualByComparingTo("1500");
        // 2000 + 2000 / 10 * 21 for food and 500 + 500 / 10 * 21 for fun
        assertThat(amount(plan, "forecast")).isEqualByComparingTo("7750");
        List<Map<String, Object>> rows = list(plan, "categories");
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Food", "Fun");
        Map<String, Object> foodRow = row(rows, food);
        assertThat(amount(foodRow, "planned")).isEqualByComparingTo("3000");
        assertThat(amount(foodRow, "actual")).isEqualByComparingTo("2000");
        assertThat(amount(foodRow, "completion")).isEqualByComparingTo("0.6667");
        assertThat(amount(foodRow, "plannedRemaining")).isEqualByComparingTo("0");
        assertThat(amount(foodRow, "forecast")).isEqualByComparingTo("6200");
        Map<String, Object> groceriesRow = row(list(foodRow, "children"), groceries);
        assertThat(amount(groceriesRow, "forecast")).isEqualByComparingTo("6200");
    }

    @Test
    void planOfPastAndFutureMonths() throws Exception {
        assign("2026-11", fun, 800);

        Map<String, Object> september = analytics("plan/2026-09");
        assertThat(september).containsEntry("daysElapsed", 30);
        Map<String, Object> foodRow = row(list(september, "categories"), food);
        // Without a plan, the completion is unknown; a past month is forecast as it was
        assertThat(foodRow.get("completion")).isNull();
        assertThat(amount(foodRow, "actual")).isEqualByComparingTo("2800");
        assertThat(amount(foodRow, "forecast")).isEqualByComparingTo("2800");
        assertThat(amount(september, "difference")).isEqualByComparingTo("-4300");

        Map<String, Object> november = analytics("plan/2026-11");
        assertThat(november).containsEntry("daysElapsed", 0);
        assertThat(list(november, "categories")).extracting(r -> r.get("name")).containsExactly("Fun");
        assertThat(amount(november, "forecast")).isEqualByComparingTo("800");
    }

    @Test
    void compareTwoPeriodsDayByDay() throws Exception {
        Map<String, Object> compare =
                analytics("compare?fromA=2026-10-01&toA=2026-10-10&fromB=2026-09-01&toB=2026-09-10");

        assertThat(amount(compare, "totalA")).isEqualByComparingTo("2500");
        assertThat(amount(compare, "totalB")).isEqualByComparingTo("4000");
        assertThat(amount(compare, "change")).isEqualByComparingTo("-1500");
        assertThat(amount(compare, "changeShare")).isEqualByComparingTo("-0.375");
        List<Map<String, Object>> days = list(compare, "days");
        assertThat(days).hasSize(10);
        assertThat(amount(days.get(2), "a")).isEqualByComparingTo("2000");
        assertThat(amount(days.get(2), "b")).isEqualByComparingTo("0");
        assertThat(amount(days.get(9), "a")).isEqualByComparingTo("2500");
        assertThat(amount(days.get(9), "b")).isEqualByComparingTo("4000");
        List<Map<String, Object>> categories = list(compare, "categories");
        assertThat(categories).extracting(c -> c.get("name")).containsExactly("Food", "Fun");
        assertThat(amount(categories.get(0), "change")).isEqualByComparingTo("-1000");
        assertThat(amount(categories.get(0), "changeShare")).isEqualByComparingTo("-0.3333");
    }

    @Test
    void compareMarksTheDaysAfterTheEndOfTheShorterPeriod() throws Exception {
        Map<String, Object> compare =
                analytics("compare?fromA=2026-10-01&toA=2026-10-10&fromB=2026-09-01&toB=2026-09-30");

        List<Map<String, Object>> days = list(compare, "days");
        assertThat(days).hasSize(30);
        assertThat(days.get(10)).containsEntry("day", 11).containsEntry("a", null);
        // The refund of the 25th lowers the cumulative spending
        assertThat(amount(days.get(29), "b")).isEqualByComparingTo("4300");
        assertThat(list(compare, "categories")).extracting(c -> c.get("name")).contains("Transport");
    }

    @Test
    void trendsFindAnomalies() throws Exception {
        Map<String, Object> trends = analytics("trends?from=2026-07&to=2026-09");

        assertThat(list(trends, "months"))
                .extracting(m -> m.get("month"))
                .containsExactly("2026-07", "2026-08", "2026-09");
        assertThat(amount(list(trends, "months").get(2), "total")).isEqualByComparingTo("4300");
        assertThat(amount(trends, "average")).isEqualByComparingTo("2100");
        List<Map<String, Object>> categories = list(trends, "categories");
        assertThat(categories).extracting(c -> c.get("name")).containsExactly("Food", "Fun", "Transport");
        Map<String, Object> foodTrend = categories.get(0);
        assertThat((List<Object>) foodTrend.get("amounts"))
                .extracting(
                        a -> new BigDecimal(a.toString()).stripTrailingZeros().toPlainString())
                .containsExactly("1000", "1000", "2800");
        assertThat(amount(foodTrend, "average")).isEqualByComparingTo("1600");
        List<Map<String, Object>> anomalies = list(foodTrend, "anomalies");
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0)).containsEntry("month", "2026-09");
        assertThat(amount(anomalies.get(0), "average")).isEqualByComparingTo("1000");
        assertThat(amount(anomalies.get(0), "ratio")).isEqualByComparingTo("2.8");
        // A category that spent only once has no average to compare with
        assertThat(list(categories.get(1), "anomalies")).isEmpty();
    }

    @Test
    void trendsDefaultToTheLastTwelveCompleteMonths() throws Exception {
        Map<String, Object> trends = analytics("trends");

        assertThat(trends).containsEntry("from", "2025-10").containsEntry("to", "2026-09");
        assertThat(list(trends, "months")).hasSize(12);
    }

    @Test
    void savingsPerMonthAndWhereTheyWent() throws Exception {
        Map<String, Object> savings = analytics("savings?from=2026-08&to=2026-10");

        assertThat(amount(savings, "target")).isEqualByComparingTo("0.2");
        // August and September are complete: (9000 + 15700) / 30000
        assertThat(amount(savings, "averageRate")).isEqualByComparingTo("0.8233");
        assertThat(amount(savings, "totalSaved")).isEqualByComparingTo("42200");
        assertThat(savings).containsEntry("bestMonth", "2026-10");
        List<Map<String, Object>> months = list(savings, "months");
        Map<String, Object> september = months.get(1);
        assertThat(amount(september, "income")).isEqualByComparingTo("20000");
        assertThat(amount(september, "expense")).isEqualByComparingTo("4300");
        assertThat(amount(september, "net")).isEqualByComparingTo("15700");
        assertThat(amount(september, "savingsRate")).isEqualByComparingTo("0.785");
        assertThat(amount(september, "cumulative")).isEqualByComparingTo("24700");
        assertThat(september).containsEntry("complete", true);
        List<Map<String, Object>> destinations = list(september, "destinations");
        assertThat(destinations).hasSize(1);
        assertThat(((Number) destinations.get(0).get("accountId")).longValue()).isEqualTo(deposit);
        assertThat(destinations.get(0)).containsEntry("name", "Deposit");
        assertThat(amount(destinations.get(0), "amount")).isEqualByComparingTo("4000");
        Map<String, Object> october = months.get(2);
        assertThat(october).containsEntry("complete", false);
        // Money moved back from the deposit
        assertThat(amount(list(october, "destinations").get(0), "amount")).isEqualByComparingTo("-1000");
    }

    @Test
    void needsWantsAndSavings() throws Exception {
        Map<String, Object> result = analytics("needs-wants?from=2026-09-01&to=2026-09-30");

        assertThat(amount(result, "income")).isEqualByComparingTo("20000");
        Map<String, Object> needs = (Map<String, Object>) result.get("needs");
        assertThat(amount(needs, "amount")).isEqualByComparingTo("2800");
        assertThat(amount(needs, "share")).isEqualByComparingTo("0.14");
        assertThat(amount(needs, "target")).isEqualByComparingTo("0.5");
        Map<String, Object> wants = (Map<String, Object>) result.get("wants");
        assertThat(amount(wants, "amount")).isEqualByComparingTo("1000");
        assertThat(amount(wants, "target")).isEqualByComparingTo("0.3");
        Map<String, Object> savings = (Map<String, Object>) result.get("savings");
        assertThat(amount(savings, "amount")).isEqualByComparingTo("15700");
        assertThat(amount(savings, "share")).isEqualByComparingTo("0.785");
        Map<String, Object> unclassified = (Map<String, Object>) result.get("unclassified");
        assertThat(amount(unclassified, "amount")).isEqualByComparingTo("500");
        assertThat(unclassified.get("target")).isNull();
    }

    @Test
    void needsWantsDefaultToTheCurrentMonth() throws Exception {
        Map<String, Object> result = analytics("needs-wants");

        assertThat(result).containsEntry("from", "2026-10-01").containsEntry("to", "2026-10-31");
        assertThat(amount((Map<String, Object>) result.get("needs"), "amount")).isEqualByComparingTo("2000");
    }

    @Test
    void invalidPeriods() throws Exception {
        invalid("compare?fromA=2026-10-10&toA=2026-10-01&fromB=2026-09-01&toB=2026-09-10");
        invalid("compare?fromA=2025-01-01&toA=2026-01-02&fromB=2026-09-01&toB=2026-09-10");
        invalid("trends?from=2023-01&to=2026-01");
        invalid("trends?from=2026-09&to=2026-08");
        invalid("savings?target=1.5");
        invalid("needs-wants?from=2026-10-02&to=2026-10-01");
    }
}
