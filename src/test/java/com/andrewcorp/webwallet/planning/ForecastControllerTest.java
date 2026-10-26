package com.andrewcorp.webwallet.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class ForecastControllerTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 15);

    private String auth;
    private Long card;
    private Long cash;
    private Long deposit;

    @BeforeEach
    void setUp() throws Exception {
        // The access token is checked against the real time, so sign in before moving the clock
        auth = loginNewUser();
        TestClock.INSTANCE.set(TODAY.atTime(LocalTime.NOON).atZone(Zones.KYIV).toInstant());
        card = account("Card", "CARD", 10000);
        cash = account("Cash", "CASH", 1000);
        deposit = account("Deposit", "DEPOSIT", 5000);
    }

    private Long account(String name, String type, double openingBalance) throws Exception {
        return createAccount(auth, name, type, "UAH", openingBalance);
    }

    private void transaction(String type, String date, Long account, double amount) throws Exception {
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of("type", type, "date", date, "accountId", account, "amount", amount),
                        auth),
                201);
    }

    private void recurring(String name, String type, Long account, Long toAccount, double amount, String start)
            throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", name,
                "type", type,
                "accountId", account,
                "amount", amount,
                "frequency", "MONTHLY",
                "startDate", start));
        if (toAccount != null) {
            body.put("toAccountId", toAccount);
        }
        json(postJson("/api/v1/recurring", body, auth), 201);
    }

    @Test
    void forecastsTheBalanceToTheEndOfTheMonth() throws Exception {
        transaction("EXPENSE", "2030-01-05", card, 900);
        transaction("INCOME", "2030-01-10", card, 3000);
        // Off the cash and cards, and entered ahead of time
        transaction("EXPENSE", "2030-01-12", deposit, 700);
        transaction("EXPENSE", "2030-01-20", cash, 100);
        recurring("Rent", "EXPENSE", card, null, 5000, "2030-01-24");
        recurring("Savings", "TRANSFER", card, deposit, 1000, "2030-01-25");
        recurring("Salary", "INCOME", card, null, 2000, "2030-01-30");
        recurring("Interest", "INCOME", deposit, null, 50, "2030-01-28");
        recurring("Pocket money", "TRANSFER", card, cash, 500, "2030-01-26");

        Map<String, Object> forecast = json(get("/api/v1/forecast").header("Authorization", auth), 200);

        assertThat(forecast).containsEntry("currency", "UAH").containsEntry("today", "2030-01-15");
        assertThat(number(forecast.get("balance"))).isEqualTo(13100);
        // 900 spent over the 11 days since the first transaction
        double daily = number(forecast.get("dailySpending"));
        assertThat(daily).isEqualTo(81.82);

        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");
        assertThat(days).hasSize(31);
        assertThat(days.get(0)).containsEntry("date", "2030-01-01").containsEntry("actual", true);
        assertThat(number(days.get(0).get("balance"))).isEqualTo(11000);
        assertThat(number(days.get(4).get("balance"))).isEqualTo(10100);
        assertThat(days.get(15)).containsEntry("date", "2030-01-16").containsEntry("actual", false);
        assertThat(number(days.get(15).get("balance"))).isCloseTo(13100 - daily, within(0.001));
        assertThat(number(days.get(30).get("balance")))
                .isCloseTo(13100 - 16 * daily - 100 - 5000 - 1000 + 2000, within(0.001));

        // Transfers between cash and cards and payments of other accounts do not change the balance
        List<Map<String, Object>> events = (List<Map<String, Object>>) forecast.get("events");
        assertThat(events).extracting(event -> event.get("name")).containsExactly("Rent", "Savings", "Salary");
        assertThat(number(events.get(1).get("amount"))).isEqualTo(-1000);

        // The operations of the day that moved the money of cash and cards, also those entered ahead of time
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) days.get(4).get("operations");
        assertThat(operations).hasSize(1);
        assertThat(number(operations.get(0).get("amount"))).isEqualTo(-900);
        assertThat(days.get(11).get("operations"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
        operations = (List<Map<String, Object>>) days.get(19).get("operations");
        assertThat(operations).hasSize(1);
        assertThat(number(operations.get(0).get("amount"))).isEqualTo(-100);

        Map<String, Object> lowest = (Map<String, Object>) forecast.get("lowest");
        assertThat(lowest).containsEntry("date", "2030-01-29");
        assertThat(number(lowest.get("balance"))).isCloseTo(13100 - 14 * daily - 6100, within(0.001));
    }

    @Test
    void expectsOverduePaymentsTomorrowAndLeavesPaidOnesOutOfTheSpending() throws Exception {
        transaction("EXPENSE", "2030-01-01", card, 450);
        recurring("Rent", "EXPENSE", card, null, 5000, "2030-01-10");
        recurring("Internet", "EXPENSE", card, null, 300, "2030-01-03");
        // Matched with the internet payment, so not part of the everyday spending
        transaction("EXPENSE", "2030-01-03", card, 300);

        Map<String, Object> forecast =
                json(get("/api/v1/forecast").param("to", "2030-01-17").header("Authorization", auth), 200);

        assertThat(number(forecast.get("dailySpending"))).isEqualTo(30);
        List<Map<String, Object>> events = (List<Map<String, Object>>) forecast.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("name", "Rent").containsEntry("overdue", true);
        List<Map<String, Object>> days = (List<Map<String, Object>>) forecast.get("days");
        assertThat(days).hasSize(17);
        assertThat(number(days.get(15).get("balance"))).isEqualTo(11000 - 750 - 30 - 5000);
    }

    @Test
    void forecastsWithoutTransactions() throws Exception {
        Map<String, Object> forecast =
                json(get("/api/v1/forecast").param("to", "2030-01-15").header("Authorization", auth), 200);

        assertThat(number(forecast.get("dailySpending"))).isZero();
        assertThat(number(forecast.get("balance"))).isEqualTo(11000);
        assertThat(forecast.get("lowest")).isNull();
    }

    @Test
    void rejectsPeriodsOutOfRange() throws Exception {
        json(get("/api/v1/forecast").param("to", "2030-01-14").header("Authorization", auth), 400);
        json(get("/api/v1/forecast").param("to", "2031-06-01").header("Authorization", auth), 400);
    }
}
