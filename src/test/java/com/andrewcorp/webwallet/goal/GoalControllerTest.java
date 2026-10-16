package com.andrewcorp.webwallet.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SuppressWarnings("unchecked")
class GoalControllerTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 3, 1);

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;
    private String otherAuth;
    private Long jar;
    private Long usd;

    @BeforeEach
    void setUp() throws Exception {
        // The access token is checked against the real time, so sign in before moving the clock
        auth = loginNewUser();
        otherAuth = loginNewUser();
        today(TODAY);
        jar = account("Jar", "SAVINGS", "UAH", 32000);
        usd = account("Dollars", "CASH", "USD", 1000);
        rates.save(new ExchangeRate("USD", TODAY, new BigDecimal("40")));
    }

    private static void today(LocalDate day) {
        TestClock.INSTANCE.set(day.atTime(LocalTime.NOON).atZone(Zones.KYIV).toInstant());
    }

    private Long account(String name, String type, String currency, double openingBalance) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", currency, "openingBalance", openingBalance),
                        auth),
                201));
    }

    private Map<String, Object> goal(String name, double target, String date, Long account) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "targetAmount", target));
        body.put("targetDate", date);
        body.put("accountId", account);
        return body;
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    void tracksTheProgressOfAGoalOnAnAccount() throws Exception {
        Map<String, Object> body = goal("Trip", 60000, "2030-12-01", jar);
        body.put("icon", "plane");
        body.put("color", "cyan");
        Long id = id(json(postJson("/api/v1/goals", body, auth), 201));

        Map<String, Object> created = json(get("/api/v1/goals/" + id).header("Authorization", auth), 200);
        assertThat(created)
                .containsEntry("currency", "UAH")
                .containsEntry("status", "ON_TRACK")
                .containsEntry("monthsLeft", 9)
                .containsEntry("startDate", "2030-03-01")
                .containsEntry("icon", "plane")
                .containsEntry("color", "cyan");
        assertThat(number(created.get("saved"))).isEqualTo(32000);
        assertThat(number(created.get("startAmount"))).isEqualTo(32000);
        assertThat(number(created.get("remaining"))).isEqualTo(28000);
        assertThat(number(created.get("progress"))).isEqualTo(0.5333);
        assertThat(number(created.get("monthlyNeeded"))).isEqualTo(3111.11);

        // Four months later nothing more was saved: expected is the start plus 4/9 of the rest, roughly
        today(LocalDate.of(2030, 7, 1));
        Map<String, Object> later = json(get("/api/v1/goals/" + id).header("Authorization", auth), 200);
        assertThat(later).containsEntry("status", "BEHIND").containsEntry("monthsLeft", 5);
        assertThat(number(later.get("expected"))).isCloseTo(32000 + 28000 * 122.0 / 275, within(0.01));
        assertThat(number(later.get("monthlyNeeded"))).isEqualTo(5600);

        // Money moved to the jar is progress
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2030-07-01",
                                "accountId",
                                usd,
                                "toAccountId",
                                jar,
                                "amount",
                                500,
                                "toAmount",
                                20000),
                        auth),
                201);
        Map<String, Object> funded = json(get("/api/v1/goals/" + id).header("Authorization", auth), 200);
        assertThat(funded).containsEntry("status", "ON_TRACK");
        assertThat(number(funded.get("saved"))).isEqualTo(52000);

        // Past the deadline it is overdue, with the whole rest needed now
        today(LocalDate.of(2030, 12, 2));
        assertThat(json(get("/api/v1/goals/" + id).header("Authorization", auth), 200))
                .containsEntry("status", "OVERDUE")
                .containsEntry("monthsLeft", 0)
                .containsEntry("monthlyNeeded", 8000.0);
    }

    @Test
    void keepsTheProgressOfAGoalByHand() throws Exception {
        Map<String, Object> body = goal("Cushion", 84000, null, null);
        body.put("saved", 52000);
        Long id = id(json(postJson("/api/v1/goals", body, auth), 201));
        assertThat(json(get("/api/v1/goals/" + id).header("Authorization", auth), 200))
                .containsEntry("status", "NO_DATE")
                .containsEntry("monthsLeft", null)
                .containsEntry("expected", null);

        Map<String, Object> topped =
                json(postJson("/api/v1/goals/" + id + "/contributions", Map.of("amount", 4000), auth), 200);
        assertThat(number(topped.get("saved"))).isEqualTo(56000);
        json(postJson("/api/v1/goals/" + id + "/contributions", Map.of("amount", -60000), auth), 400);

        body.put("saved", 56000);
        body.put("achieved", true);
        Map<String, Object> achieved = json(putJson("/api/v1/goals/" + id, body, auth), 200);
        assertThat(achieved).containsEntry("status", "ACHIEVED").containsEntry("achievedOn", "2030-03-01");
        body.remove("achieved");
        body.put("name", "Safety cushion");
        assertThat(json(putJson("/api/v1/goals/" + id, body, auth), 200))
                .containsEntry("name", "Safety cushion")
                .containsEntry("achievedOn", "2030-03-01");
        body.put("achieved", false);
        assertThat(json(putJson("/api/v1/goals/" + id, body, auth), 200)).containsEntry("status", "NO_DATE");

        json(delete("/api/v1/goals/" + id).header("Authorization", auth), 204);
        json(get("/api/v1/goals/" + id).header("Authorization", auth), 404);
    }

    @Test
    void listsGoalsWithTotalsInTheBaseCurrency() throws Exception {
        json(postJson("/api/v1/goals", goal("Trip", 60000, "2030-12-01", jar), auth), 201);
        // In dollars, from the dollar account: 1000 of 2000 USD
        json(postJson("/api/v1/goals", goal("Laptop", 2000, "2030-05-01", usd), auth), 201);
        Map<String, Object> manual = goal("Bike", 10000, null, null);
        manual.put("sortOrder", -1);
        json(postJson("/api/v1/goals", manual, auth), 201);

        Map<String, Object> list = json(get("/api/v1/goals").header("Authorization", auth), 200);
        List<Map<String, Object>> goals = (List<Map<String, Object>>) list.get("goals");
        assertThat(goals).extracting(goal -> goal.get("name")).containsExactly("Bike", "Trip", "Laptop");
        assertThat(goals.get(2)).containsEntry("currency", "USD").containsEntry("monthsLeft", 2);
        assertThat(list).containsEntry("currency", "UAH");
        assertThat(number(list.get("saved"))).isEqualTo(32000 + 40000);
        assertThat(number(list.get("target"))).isEqualTo(60000 + 80000 + 10000);
        // 3111.11 a month for the trip and 500 USD for the laptop
        assertThat(number(list.get("monthlyNeeded"))).isEqualTo(3111.11 + 20000);
    }

    @Test
    void rejectsInvalidGoals() throws Exception {
        json(postJson("/api/v1/goals", goal("Trip", 0, null, null), auth), 400);
        json(postJson("/api/v1/goals", goal("", 1000, null, null), auth), 400);
        json(postJson("/api/v1/goals", goal("Trip", 1000, null, 0L), auth), 400);
        Map<String, Object> icon = goal("Trip", 1000, null, null);
        icon.put("icon", "Plane!");
        json(postJson("/api/v1/goals", icon, auth), 400);

        Long id = id(json(postJson("/api/v1/goals", goal("Trip", 1000, null, jar), auth), 201));
        json(postJson("/api/v1/goals/" + id + "/contributions", Map.of("amount", 100), auth), 400);
        json(get("/api/v1/goals/" + id).header("Authorization", otherAuth), 404);
    }
}
