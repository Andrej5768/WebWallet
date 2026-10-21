package com.andrewcorp.webwallet.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.telegram.TelegramApi.Button;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.user.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(
        properties = {
            "app.telegram.bot-token=123:test",
            "app.telegram.bot-username=TestWalletBot",
            "app.telegram.webhook-secret=" + TelegramNotificationsTest.SECRET
        })
class TelegramNotificationsTest extends AbstractIntegrationTest {

    static final String SECRET = "webhook-secret";
    private static final long CHAT = 777L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @MockitoBean
    private TelegramClient client;

    @Autowired
    private TelegramNotifications notifications;

    @Autowired
    private JdbcTemplate jdbc;

    private String auth;
    private User user;
    private Long card;
    private Long food;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        card = id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", "Card", "type", "CARD", "currency", "UAH", "openingBalance", 100000),
                        auth),
                201));
        food = id(json(postJson("/api/v1/categories", Map.of("name", "Food", "kind", "EXPENSE"), auth), 201));
        jdbc.update("insert into telegram_links (user_id, chat_id) values (?, ?)", user.getId(), CHAT);
    }

    @Test
    void remindsOfAPaymentTheDayBeforeAndOnTheDayOnce() throws Exception {
        at(2026, 10, 20, 9);
        Long rent = recurring("Rent", LocalDate.of(2026, 10, 21));

        notifications.daily();
        notifications.daily();

        verify(client).sendMessage(eq(CHAT), contains("Payment tomorrow: Rent"), any(), any());

        at(2026, 10, 21, 9);
        notifications.daily();

        ArgumentCaptor<Keyboard> keyboard = ArgumentCaptor.forClass(Keyboard.class);
        verify(client).sendMessage(eq(CHAT), contains("Payment today: Rent"), keyboard.capture(), any());
        Button paid = keyboard.getValue().rows().get(0).get(0);
        assertThat(paid.callbackData()).isEqualTo("rp:" + rent + ":2026-10-21");

        callback(paid.callbackData());

        assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class))
                .isEqualTo(1);
        verify(client).editMessageText(eq(CHAT), eq(10L), contains("The operation is recorded"), any());
    }

    @Test
    void notifiesAtTheHourOfTheUserOnly() throws Exception {
        at(2026, 10, 20, 9);
        recurring("Rent", LocalDate.of(2026, 10, 21));
        jdbc.update("insert into notification_settings (user_id, notify_hour) values (?, 20)", user.getId());

        notifications.daily();
        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any());

        at(2026, 10, 20, 20);
        notifications.daily();
        verify(client).sendMessage(eq(CHAT), contains("Payment tomorrow"), any(), any());
    }

    @Test
    void switchedOffKindIsNotSent() throws Exception {
        at(2026, 10, 20, 9);
        recurring("Rent", LocalDate.of(2026, 10, 21));
        jdbc.update("insert into notification_settings (user_id, recurring) values (?, false)", user.getId());

        notifications.daily();

        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void budgetWarnsAtEightyPercentAndAtTheLimit() throws Exception {
        at(2026, 10, 10, 9);
        json(putJson("/api/v1/budget/2026-10/categories/" + food, Map.of("assigned", 1000), auth), 200);
        expense(LocalDate.of(2026, 10, 5), 850);

        notifications.daily();
        verify(client).sendMessage(eq(CHAT), contains("Budget \"Food\": 85 %"), any(), any());

        at(2026, 10, 11, 9);
        notifications.daily();
        verify(client, never()).sendMessage(eq(CHAT), contains("100 %"), any(), any());

        expense(LocalDate.of(2026, 10, 11), 200);
        at(2026, 10, 12, 9);
        notifications.daily();
        verify(client).sendMessage(eq(CHAT), contains("Budget \"Food\": 105 %"), any(), any());
    }

    @Test
    void weeklyReportComparesWithTheSameDaysOfThePreviousMonthWithAChart() throws Exception {
        expense(LocalDate.of(2026, 9, 3), 1000);
        expense(LocalDate.of(2026, 10, 3), 1500);
        at(2026, 10, 19, 9);

        notifications.daily();

        ArgumentCaptor<byte[]> chart = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
        verify(client).sendPhoto(eq(CHAT), chart.capture(), caption.capture(), any());
        assertThat(chart.getValue()).startsWith(PNG);
        assertThat(caption.getValue())
                .contains("Spending 1–18 October")
                .contains("50 % more than 1–18 September")
                .contains("Food — +500 ₴ · +50 %");
    }

    @Test
    void noWeeklyReportInTheFirstWeekOfAMonth() throws Exception {
        expense(LocalDate.of(2026, 10, 3), 1500);
        at(2026, 11, 2, 9);

        notifications.daily();

        verify(client, never()).sendPhoto(anyLong(), any(), anyString(), any());
    }

    @Test
    void monthlyReportOnTheFirst() throws Exception {
        expense(LocalDate.of(2026, 9, 3), 1000);
        expense(LocalDate.of(2026, 10, 3), 800);
        at(2026, 11, 1, 9);

        notifications.daily();

        ArgumentCaptor<byte[]> chart = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
        verify(client).sendPhoto(eq(CHAT), chart.capture(), caption.capture(), any());
        assertThat(chart.getValue()).startsWith(PNG);
        assertThat(caption.getValue())
                .contains("<b>October</b> against the previous month")
                .contains("Spending — 800 ₴ · −20 %");
    }

    @Test
    void bankWithARejectedTokenIsReportedOnceAndNotAtNight() throws Exception {
        jdbc.update("""
                insert into bank_connections (user_id, provider, encrypted_token, webhook_secret, last_error, next_sync_at,
                                              created_at)
                values (?, 'MONOBANK', 'x', 'secret', 'INVALID_EXTERNAL_TOKEN', now(), now())""", user.getId());

        at(2026, 10, 20, 23);
        notifications.banks();
        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any());

        at(2026, 10, 21, 8);
        notifications.banks();
        notifications.banks();
        verify(client).sendMessage(eq(CHAT), contains("synchronization stopped"), any(), any());

        // Solved, then rejected again: reported again
        jdbc.update("update bank_connections set last_error = null");
        notifications.banks();
        jdbc.update("update bank_connections set last_error = 'INVALID_EXTERNAL_TOKEN'");
        clearInvocations(client);
        notifications.banks();
        verify(client, atLeastOnce()).sendMessage(eq(CHAT), contains("synchronization stopped"), any(), any());
    }

    // helpers

    private static void at(int year, int month, int day, int hour) {
        TestClock.INSTANCE.set(
                LocalDateTime.of(year, month, day, hour, 0).atZone(Zones.KYIV).toInstant());
    }

    private static String contains(String text) {
        return org.mockito.ArgumentMatchers.contains(text);
    }

    private Long recurring(String name, LocalDate start) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/recurring",
                        Map.of(
                                "name",
                                name,
                                "type",
                                "EXPENSE",
                                "accountId",
                                card,
                                "amount",
                                15000,
                                "frequency",
                                "MONTHLY",
                                "startDate",
                                start.toString()),
                        auth),
                201));
    }

    private void expense(LocalDate date, double amount) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "type", "EXPENSE", "date", date.toString(), "accountId", card, "amount", amount, "categoryId", food));
        json(postJson("/api/v1/transactions", body, auth), 201);
    }

    private void callback(String data) throws Exception {
        Map<String, Object> message =
                Map.of("message_id", 10, "chat", Map.of("id", CHAT, "type", "private"), "text", "…");
        Map<String, Object> update = Map.of(
                "update_id",
                1,
                "callback_query",
                Map.of("id", "q", "from", Map.of("id", 42), "message", message, "data", data));
        mockMvc.perform(post(TelegramWebhookController.PATH)
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(update)))
                .andExpect(status().isOk());
    }
}
