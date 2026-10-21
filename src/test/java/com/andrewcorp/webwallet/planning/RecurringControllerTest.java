package com.andrewcorp.webwallet.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SuppressWarnings("unchecked")
class RecurringControllerTest extends AbstractIntegrationTest {

    /** Today of the tests; series created now get this creation date, so automatic payments start from it. */
    private static final LocalDate TODAY = LocalDate.now(Zones.KYIV);

    @Autowired
    private RecurringTask recurringTask;

    private String auth;
    private Long card;
    private Long cash;
    private Long deposit;
    private Long rent;
    private Long salary;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        card = account("Card", "CARD");
        cash = account("Cash", "CASH");
        deposit = account("Deposit", "DEPOSIT");
        rent = id(json(postJson("/api/v1/categories", Map.of("name", "Rent", "kind", "EXPENSE"), auth), 201));
        salary = id(json(postJson("/api/v1/categories", Map.of("name", "Salary", "kind", "INCOME"), auth), 201));
    }

    private Long account(String name, String type) throws Exception {
        return id(json(postJson("/api/v1/accounts", Map.of("name", name, "type", type, "currency", "UAH"), auth), 201));
    }

    private Map<String, Object> series(String name, String type, Long account, double amount, LocalDate start) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", name,
                "type", type,
                "accountId", account,
                "amount", amount,
                "frequency", "MONTHLY",
                "startDate", start.toString()));
        if (type.equals("EXPENSE")) {
            body.put("categoryId", rent);
            body.put("payeeName", "Landlord");
        }
        return body;
    }

    private Long create(Map<String, Object> body) throws Exception {
        return id(json(postJson("/api/v1/recurring", body, auth), 201));
    }

    private Long transaction(String type, LocalDate date, Long account, double amount, String payee) throws Exception {
        Map<String, Object> body =
                new HashMap<>(Map.of("type", type, "date", date.toString(), "accountId", account, "amount", amount));
        if (payee != null) {
            body.put("payeeName", payee);
        }
        return id(json(postJson("/api/v1/transactions", body, auth), 201));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return jsonMapper.readValue(body, List.class);
    }

    private List<Map<String, Object>> occurrences(LocalDate from, LocalDate to) throws Exception {
        return list(get("/api/v1/recurring/occurrences")
                .param("from", from.toString())
                .param("to", to.toString()));
    }

    private Map<String, Object> occurrence(Long recurring, LocalDate date) throws Exception {
        return occurrences(date, date).stream()
                .filter(occurrence -> ((Number) occurrence.get("recurringId")).longValue() == recurring)
                .findFirst()
                .orElseThrow();
    }

    /** Moves the clock to noon of the day; the access token is checked against the real time and stays valid. */
    private static void today(LocalDate day) {
        TestClock.INSTANCE.set(day.atTime(LocalTime.NOON).atZone(Zones.KYIV).toInstant());
    }

    private String url(Long recurring, LocalDate date, String action) {
        return "/api/v1/recurring/" + recurring + "/occurrences/" + date + (action.isEmpty() ? "" : "/" + action);
    }

    @Test
    void createsAndListsRecurringPayments() throws Exception {
        LocalDate start = TODAY.plusDays(9);
        Map<String, Object> body = series("Rent", "EXPENSE", card, 14000, start);
        body.put("comment", "Flat");
        Long id = create(body);

        Map<String, Object> created = json(get("/api/v1/recurring/" + id).header("Authorization", auth), 200);
        assertThat(created)
                .containsEntry("name", "Rent")
                .containsEntry("type", "EXPENSE")
                .containsEntry("currency", "UAH")
                .containsEntry("frequency", "MONTHLY")
                .containsEntry("interval", 1)
                .containsEntry("autoCreate", false)
                .containsEntry("variableAmount", false)
                .containsEntry("tolerance", 10)
                .containsEntry("nextDate", start.toString())
                .containsEntry("comment", "Flat");
        assertThat(((Number) created.get("categoryId")).longValue()).isEqualTo(rent);
        assertThat((Map<String, Object>) created.get("payee")).containsEntry("name", "Landlord");

        Map<String, Object> utilities = series("Utilities", "EXPENSE", card, 2300, TODAY.plusDays(5));
        utilities.put("variableAmount", true);
        create(utilities);
        List<Map<String, Object>> list = list(get("/api/v1/recurring"));
        assertThat(list).extracting(item -> item.get("name")).containsExactly("Rent", "Utilities");
        assertThat(list.get(1)).containsEntry("tolerance", 25);

        body.put("name", "Flat rent");
        body.put("interval", 2);
        assertThat(json(putJson("/api/v1/recurring/" + id, body, auth), 200))
                .containsEntry("name", "Flat rent")
                .containsEntry("interval", 2);

        json(delete("/api/v1/recurring/" + id).header("Authorization", auth), 204);
        json(get("/api/v1/recurring/" + id).header("Authorization", auth), 404);
    }

    @Test
    void listsOccurrencesOfThePeriod() throws Exception {
        Long id = create(series("Rent", "EXPENSE", card, 14000, TODAY.plusDays(3)));
        Map<String, Object> weekly = series("Pocket money", "TRANSFER", card, 500, TODAY.plusDays(1));
        weekly.put("toAccountId", cash);
        weekly.put("frequency", "WEEKLY");
        Long pocket = create(weekly);

        List<Map<String, Object>> occurrences = occurrences(TODAY, TODAY.plusDays(20));
        assertThat(occurrences)
                .extracting(occurrence -> occurrence.get("date"))
                .containsExactly(
                        TODAY.plusDays(1).toString(),
                        TODAY.plusDays(3).toString(),
                        TODAY.plusDays(8).toString(),
                        TODAY.plusDays(15).toString());
        assertThat(occurrences.get(1))
                .containsEntry("name", "Rent")
                .containsEntry("status", "PENDING")
                .containsEntry("overdue", false)
                .containsEntry("amount", 14000.0)
                .containsEntry("transactionId", null);
        assertThat(occurrences.get(0))
                .containsEntry("type", "TRANSFER")
                .containsEntry("toAmount", 500.0)
                .containsEntry("recurringId", pocket.intValue());

        // Past the end date nothing more is scheduled
        weekly.put("endDate", TODAY.plusDays(10).toString());
        json(putJson("/api/v1/recurring/" + pocket, weekly, auth), 200);
        assertThat(occurrences(TODAY, TODAY.plusDays(20))).hasSize(3);

        // Overdue for longer than the lookback, a pending occurrence is dropped
        today(TODAY.plusDays(3 + RecurringService.LOOKBACK_DAYS + 1));
        assertThat(occurrences(TODAY, TODAY.plusDays(3))).isEmpty();
        Map<String, Object> next = json(get("/api/v1/recurring/" + id).header("Authorization", auth), 200);
        assertThat(next.get("nextDate"))
                .isEqualTo(TODAY.plusDays(3).plusMonths(1).toString());
    }

    @Test
    void matchesTransactionsWithinTheTolerance() throws Exception {
        LocalDate due = TODAY.plusDays(4);
        Long id = create(series("Rent", "EXPENSE", card, 14000, due));
        // Too far off in amount, another account, or another payee and category: not the rent
        transaction("EXPENSE", due, card, 15500, null);
        transaction("EXPENSE", due, cash, 14000, null);
        Long food = id(json(postJson("/api/v1/categories", Map.of("name", "Food", "kind", "EXPENSE"), auth), 201));
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                due.minusDays(1).toString(),
                                "accountId",
                                card,
                                "amount",
                                14000,
                                "payeeName",
                                "Shop",
                                "categoryId",
                                food),
                        auth),
                201);
        assertThat(occurrence(id, due)).containsEntry("status", "PENDING");

        // Two days early and a little less: a bank operation of the rent
        Long paid = transaction("EXPENSE", due.minusDays(2), card, 13900, null);
        Map<String, Object> occurrence = occurrence(id, due);
        assertThat(occurrence).containsEntry("status", "PAID");
        assertThat(((Number) occurrence.get("transactionId")).longValue()).isEqualTo(paid);

        Map<String, Object> transaction =
                json(get("/api/v1/transactions/" + paid).header("Authorization", auth), 200);
        assertThat((Map<String, Object>) transaction.get("recurring"))
                .containsEntry("name", "Rent")
                .containsEntry("dueDate", due.toString());
        assertThat(json(get("/api/v1/transactions").header("Authorization", auth), 200)
                        .toString())
                .contains("dueDate=" + due);
    }

    @Test
    void prefersTheSamePayee() throws Exception {
        LocalDate due = TODAY.plusDays(4);
        Long id = create(series("Rent", "EXPENSE", card, 14000, due));
        transaction("EXPENSE", due, card, 14000, null);
        Long landlord = transaction("EXPENSE", due.plusDays(1), card, 14100, "Landlord");

        assertThat(((Number) occurrence(id, due).get("transactionId")).longValue())
                .isEqualTo(landlord);
    }

    @Test
    void matchesTransfersBetweenTheAccounts() throws Exception {
        LocalDate due = TODAY.plusDays(2);
        Map<String, Object> body = series("Savings", "TRANSFER", card, 5000, due);
        body.put("toAccountId", deposit);
        Long id = create(body);
        Long transfer = id(json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                due.toString(),
                                "accountId",
                                card,
                                "toAccountId",
                                deposit,
                                "amount",
                                5000),
                        auth),
                201));

        assertThat(((Number) occurrence(id, due).get("transactionId")).longValue())
                .isEqualTo(transfer);
    }

    @Test
    void confirmsAnOccurrence() throws Exception {
        LocalDate due = TODAY.plusDays(4);
        Long id = create(series("Rent", "EXPENSE", card, 14000, due));

        Map<String, Object> confirmed = json(postJson(url(id, due, "confirm"), Map.of("amount", 14200), auth), 200);
        assertThat(confirmed).containsEntry("status", "PAID");
        Long transactionId = ((Number) confirmed.get("transactionId")).longValue();
        Map<String, Object> transaction =
                json(get("/api/v1/transactions/" + transactionId).header("Authorization", auth), 200);
        // Ahead of the date, the payment is made today
        assertThat(transaction)
                .containsEntry("date", TODAY.toString())
                .containsEntry("amount", 14200.0)
                .containsEntry("type", "EXPENSE");
        assertThat((Map<String, Object>) transaction.get("payee")).containsEntry("name", "Landlord");
        assertThat(transaction.get("splits").toString()).contains("categoryId=" + rent);

        json(post(url(id, due, "confirm")).header("Authorization", auth), 400);

        // A deleted transaction makes the occurrence pending, and the payment is not created or matched again
        json(delete("/api/v1/transactions/" + transactionId).header("Authorization", auth), 204);
        transaction("EXPENSE", due, card, 14000, null);
        assertThat(occurrence(id, due)).containsEntry("status", "PENDING").containsEntry("transactionId", null);

        // Without a body, a payment due in the past is made on its date
        LocalDate next = due.plusMonths(1);
        today(next.plusDays(RecurringService.LOOKBACK_DAYS - 1));
        Map<String, Object> late = json(post(url(id, next, "confirm")).header("Authorization", auth), 200);
        assertThat(json(
                        get("/api/v1/transactions/" + ((Number) late.get("transactionId")).longValue())
                                .header("Authorization", auth),
                        200))
                .containsEntry("date", next.toString())
                .containsEntry("amount", 14000.0);
    }

    @Test
    void linksSkipsAndResetsOccurrences() throws Exception {
        LocalDate due = TODAY.plusDays(4);
        Long id = create(series("Rent", "EXPENSE", card, 14000, due));
        // Paid from another card: not matched automatically, but offered for linking
        Long other = transaction("EXPENSE", due.minusDays(1), cash, 14000, null);
        transaction("EXPENSE", due, cash, 50, null);
        assertThat(occurrence(id, due)).containsEntry("status", "PENDING");

        List<?> candidates = jsonMapper.readValue(
                mockMvc.perform(get(url(id, due, "candidates")).header("Authorization", auth))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                List.class);
        assertThat(candidates)
                .extracting(c -> ((Number) ((Map<?, ?>) c).get("id")).longValue())
                .containsExactly(other);

        Map<String, Object> linked = json(postJson(url(id, due, "link"), Map.of("transactionId", other), auth), 200);
        assertThat(linked).containsEntry("status", "PAID");
        // A transaction pays only one occurrence
        json(postJson(url(id, due.plusMonths(1), "link"), Map.of("transactionId", other), auth), 400);
        json(postJson(url(id, due.plusMonths(1), "link"), Map.of("transactionId", 0), auth), 400);

        // Unlinked, the transaction stays and is not matched again
        assertThat(json(delete(url(id, due, "")).header("Authorization", auth), 200))
                .containsEntry("status", "PENDING");
        json(get("/api/v1/transactions/" + other).header("Authorization", auth), 200);

        assertThat(json(post(url(id, due, "skip")).header("Authorization", auth), 200))
                .containsEntry("status", "SKIPPED")
                .containsEntry("transactionId", null);
        assertThat(json(get("/api/v1/recurring/" + id).header("Authorization", auth), 200))
                .containsEntry("nextDate", due.plusMonths(1).toString());

        // Dates off the schedule do not exist
        json(post(url(id, due.plusDays(1), "skip")).header("Authorization", auth), 404);
        json(get(url(id, due.minusMonths(1), "candidates")).header("Authorization", auth), 404);
    }

    @Test
    void createsAutomaticPaymentsOnTheDueDate() throws Exception {
        Map<String, Object> body = series("Salary", "INCOME", card, 52000, TODAY.minusMonths(1));
        body.put("categoryId", salary);
        body.put("autoCreate", true);
        Long id = create(body);
        // Before the series existed nothing is created; today's one is
        assertThat(occurrences(TODAY.minusMonths(1), TODAY))
                .extracting(occurrence -> occurrence.get("status"))
                .containsExactly("PENDING", "PAID");

        today(TODAY.plusMonths(1));
        recurringTask.refresh();
        Map<String, Object> paid = occurrence(id, TODAY.plusMonths(1));
        assertThat(paid).containsEntry("status", "PAID");
        Map<String, Object> transaction = json(
                get("/api/v1/transactions/" + ((Number) paid.get("transactionId")).longValue())
                        .header("Authorization", auth),
                200);
        assertThat(transaction)
                .containsEntry("type", "INCOME")
                .containsEntry("amount", 52000.0)
                .containsEntry("date", TODAY.plusMonths(1).toString());
    }

    @Test
    void movedScheduleDoesNotCreateThePaymentAgain() throws Exception {
        Map<String, Object> body = series("Salary", "INCOME", card, 52000, TODAY);
        body.put("categoryId", salary);
        body.put("autoCreate", true);
        Long id = create(body);
        assertThat(occurrence(id, TODAY)).containsEntry("status", "PAID");

        // The payment of this month moves five days later
        body.put("startDate", TODAY.plusDays(5).toString());
        json(putJson("/api/v1/recurring/" + id, body, auth), 200);
        today(TODAY.plusDays(5));
        recurringTask.refresh();

        assertThat(occurrence(id, TODAY.plusDays(5))).containsEntry("status", "PAID");
        assertThat(json(get("/api/v1/transactions").header("Authorization", auth), 200))
                .containsEntry("totalItems", 1);
    }

    @Test
    void rejectsInvalidRecurringPayments() throws Exception {
        Map<String, Object> transfer = series("Savings", "TRANSFER", card, 1000, TODAY);
        json(postJson("/api/v1/recurring", transfer, auth), 400);
        transfer.put("toAccountId", card);
        json(postJson("/api/v1/recurring", transfer, auth), 400);
        transfer.put("toAccountId", deposit);
        transfer.put("categoryId", rent);
        json(postJson("/api/v1/recurring", transfer, auth), 400);

        Map<String, Object> expense = series("Rent", "EXPENSE", card, 14000, TODAY);
        expense.put("endDate", TODAY.minusDays(1).toString());
        json(postJson("/api/v1/recurring", expense, auth), 400);
        expense.put("endDate", null);
        expense.put("toAccountId", deposit);
        json(postJson("/api/v1/recurring", expense, auth), 400);
        expense.remove("toAccountId");
        expense.put("categoryId", salary);
        json(postJson("/api/v1/recurring", expense, auth), 400);
        expense.put("categoryId", rent);
        expense.put("interval", 0);
        json(postJson("/api/v1/recurring", expense, auth), 400);
        expense.put("interval", 1);
        expense.put("startDate", "1980-01-01");
        json(postJson("/api/v1/recurring", expense, auth), 400);

        json(
                get("/api/v1/recurring/occurrences")
                        .param("from", TODAY.toString())
                        .param("to", TODAY.minusDays(1).toString())
                        .header("Authorization", auth),
                400);
        json(
                get("/api/v1/recurring/occurrences")
                        .param("from", TODAY.toString())
                        .param("to", TODAY.plusYears(2).toString())
                        .header("Authorization", auth),
                400);
    }

    @Test
    void hidesRecurringPaymentsOfOtherUsers() throws Exception {
        Long id = create(series("Rent", "EXPENSE", card, 14000, TODAY));
        String other = loginNewUser();
        json(get("/api/v1/recurring/" + id).header("Authorization", other), 404);
        json(delete("/api/v1/recurring/" + id).header("Authorization", other), 404);
        json(post(url(id, TODAY, "skip")).header("Authorization", other), 404);
        assertThat(json(get("/api/v1/recurring/" + id).header("Authorization", auth), 200))
                .containsEntry("name", "Rent");
    }

    @Test
    void matchesTransactionsEnteredAheadOfTime() throws Exception {
        LocalDate due = TODAY.plusDays(20);
        Long id = create(series("Rent", "EXPENSE", card, 14000, due));
        Long paid = transaction("EXPENSE", due, card, 14000, null);

        Map<String, Object> occurrence = occurrence(id, due);

        assertThat(occurrence).containsEntry("status", "PAID");
        assertThat(((Number) occurrence.get("transactionId")).longValue()).isEqualTo(paid);
    }
}
