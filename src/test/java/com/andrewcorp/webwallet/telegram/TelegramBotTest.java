package com.andrewcorp.webwallet.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.telegram.TelegramApi.Button;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.user.User;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
            "app.telegram.webhook-secret=" + TelegramBotTest.SECRET
        })
class TelegramBotTest extends AbstractIntegrationTest {

    static final String SECRET = "webhook-secret";
    private static final long CHAT = 555L;

    @MockitoBean
    private TelegramClient client;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong updateIds = new AtomicLong();
    // Message ids differ between the tests: the bot remembers the answered questions by them
    private final AtomicLong messageIds = new AtomicLong(System.nanoTime() % 1_000_000_000);

    private String auth;
    private User user;
    private Long card;
    private Long cash;
    private Long coffee;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        card = account("Monobank Чорна", "CARD", "UAH");
        cash = account("Готівка", "CASH", "UAH");
        Long food = category("Їжа", "EXPENSE", null);
        coffee = category("Кава", "EXPENSE", food);
        when(client.sendMessage(anyLong(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> new TelegramApi.Message(messageIds.incrementAndGet(), null, null, null, null));
    }

    @Test
    void linksTheChatByTheCodeOfTheLink() throws Exception {
        Map<String, Object> link = json(postJson("/api/v1/telegram/link", Map.of("password", PASSWORD), auth), 200);
        String url = (String) link.get("url");
        assertThat(url).startsWith("https://t.me/TestWalletBot?start=");

        message("/start " + url.substring(url.indexOf('=') + 1));

        assertThat(lastText()).contains("Telegram is connected to WebWallet").contains(user.getEmail());
        Map<String, Object> status = json(get("/api/v1/telegram").header("Authorization", auth), 200);
        assertThat(status)
                .containsEntry("enabled", true)
                .containsEntry("linked", true)
                .containsEntry("username", "olena");
    }

    @Test
    void linkNeedsThePassword() throws Exception {
        mockMvc.perform(postJson("/api/v1/telegram/link", Map.of(), auth)).andExpect(status().isBadRequest());
        mockMvc.perform(postJson("/api/v1/telegram/link", Map.of("password", "wrong-password"), auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void newPasswordDisconnectsTheChat() throws Exception {
        link();

        mockMvc.perform(postJson(
                        "/api/v1/me/password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "newPassword123"),
                        auth))
                .andExpect(status().isOk());

        assertThat(count("select count(*) from telegram_links")).isZero();
        assertThat(lastText()).contains("disconnected");
    }

    @Test
    void codeWorksOnce() throws Exception {
        String code = code();
        message("/start " + code);
        message("/stop");

        message("/start " + code);

        assertThat(lastText()).contains("invalid or has expired");
    }

    @Test
    void chatOfAnotherUserIsNotTaken() throws Exception {
        link();
        User other = createVerifiedUser();
        String otherAuth = bearer(login(other.getEmail(), PASSWORD));
        Map<String, Object> link =
                json(postJson("/api/v1/telegram/link", Map.of("password", PASSWORD), otherAuth), 200);
        String url = (String) link.get("url");

        message("/start " + url.substring(url.indexOf('=') + 1));

        assertThat(lastText()).contains("already connected");
    }

    @Test
    void unlinkedChatIsToldHowToConnect() throws Exception {
        message("150 coffee");

        assertThat(lastText()).contains("not connected");
        assertThat(count("select count(*) from transactions")).isZero();
    }

    @Test
    void recordsAnExpenseWithTheCategoryOfItsName() throws Exception {
        link();

        message("150 кава");

        Map<String, Object> transaction = onlyTransaction();
        assertThat(transaction).containsEntry("type", "EXPENSE").containsEntry("comment", "кава");
        assertThat(((Number) transaction.get("accountId")).longValue()).isEqualTo(card);
        assertThat(((Number) transaction.get("category_id")).longValue()).isEqualTo(coffee);
        assertThat(lastText()).contains("−150.00 ₴").contains("Їжа › Кава").contains("Monobank Чорна · today");
        assertThat(lastKeyboard().rows()).hasSize(2);
    }

    @Test
    void twoAccountsMakeATransfer() throws Exception {
        link();

        message("2000 моно готівку");

        Map<String, Object> transaction = onlyTransaction();
        assertThat(transaction).containsEntry("type", "TRANSFER");
        assertThat(((Number) transaction.get("to_account_id")).longValue()).isEqualTo(cash);
        assertThat(lastText()).contains("Monobank Чорна → Готівка");
    }

    @Test
    void asksWhichAccountAnAmbiguousWordMeans() throws Exception {
        link();
        Long white = account("Monobank Біла", "CARD", "UAH");

        long question = message("300 моно таксі");

        assertThat(count("select count(*) from transactions")).isZero();
        assertThat(lastText()).contains("Which account?");
        Button pick = lastKeyboard().rows().get(0).stream()
                .filter(button -> button.text().equals("Monobank Біла"))
                .findFirst()
                .orElseThrow();

        callback(question, pick.callbackData(), "300 моно таксі");

        assertThat(((Number) onlyTransaction().get("accountId")).longValue()).isEqualTo(white);
    }

    @Test
    void secondPressOfTheAccountChoiceRecordsNothing() throws Exception {
        link();
        account("Monobank Біла", "CARD", "UAH");
        long question = message("300 моно таксі");
        String data = lastKeyboard().rows().get(0).get(0).callbackData();

        callback(question, data, "300 моно таксі");
        callback(question, data, "300 моно таксі");

        assertThat(count("select count(*) from transactions")).isEqualTo(1);
    }

    @Test
    void transferDirectionFromTheWords() throws Exception {
        link();

        message("2000 на готівку з моно");

        Map<String, Object> transaction = onlyTransaction();
        assertThat(((Number) transaction.get("accountId")).longValue()).isEqualTo(card);
        assertThat(((Number) transaction.get("to_account_id")).longValue()).isEqualTo(cash);
    }

    @Test
    void amountInAnotherCurrencyIsNotRecordedOnTheAccount() throws Exception {
        link();

        message("20 usd обід");
        assertThat(lastText()).contains("no account in USD");

        Long dollars = account("Wise", "CARD", "USD");
        message("20 usd обід");
        assertThat(((Number) onlyTransaction().get("accountId")).longValue()).isEqualTo(dollars);

        message("100 грн wise");
        assertThat(lastText()).contains("is in USD, but the amount is in UAH");
    }

    @Test
    void disabledUserGetsNothing() throws Exception {
        link();
        jdbcUpdate("update users set enabled = false");

        message("150 кава");

        assertThat(count("select count(*) from transactions")).isZero();
        assertThat(lastText()).contains("not connected");
    }

    @Test
    void buttonsSetTheCategoryAndCancel() throws Exception {
        link();
        Long groceries = category("Продукти", "EXPENSE", null);
        long reply = message("2000 банкомат");
        Long id = ((Number) onlyTransaction().get("id")).longValue();
        assertThat(onlyTransaction().get("category_id")).isNull();

        callback(reply, "c:" + id, null);
        assertThat(editedKeyboard().rows().stream().flatMap(List::stream).map(Button::callbackData))
                .contains("cs:" + id + ":" + groceries);

        callback(reply, "cs:" + id + ":" + groceries, null);
        assertThat(((Number) onlyTransaction().get("category_id")).longValue()).isEqualTo(groceries);
        assertThat(editedText()).contains("Next time \"банкомат\" goes to this category");

        // The description now brings the category by itself
        message("100 банкомат");
        assertThat(count("select count(*) from transaction_splits where category_id = " + groceries))
                .isEqualTo(2);

        callback(reply, "d:" + id, null);
        assertThat(count("select count(*) from transactions where id = " + id)).isZero();
        assertThat(editedText()).contains("Cancelled");
    }

    @Test
    void expenseBecomesATransfer() throws Exception {
        link();
        long reply = message("2000 банкомат");
        Long id = ((Number) onlyTransaction().get("id")).longValue();

        callback(reply, "xs:" + id + ":" + cash, null);

        Map<String, Object> transaction = onlyTransaction();
        assertThat(transaction).containsEntry("type", "TRANSFER");
        assertThat(((Number) transaction.get("to_account_id")).longValue()).isEqualTo(cash);
    }

    @Test
    void buttonsOfAnotherUserDoNothing() throws Exception {
        link();
        long reply = message("150 кава");
        Long id = ((Number) onlyTransaction().get("id")).longValue();
        jdbcUpdate("update telegram_links set chat_id = 777");
        User other = createVerifiedUser();
        jdbcUpdate("insert into telegram_links (user_id, chat_id) values (" + other.getId() + ", " + CHAT + ")");

        callback(reply, "d:" + id, null);

        assertThat(count("select count(*) from transactions where id = " + id)).isEqualTo(1);
    }

    @Test
    void balanceCommand() throws Exception {
        link();

        message("/balance");

        assertThat(lastText()).contains("Monobank Чорна — 1,000.00 ₴").contains("Total");
    }

    @Test
    void plannedCommandListsThePaymentsOfThirtyDays() throws Exception {
        link();
        LocalDate today = LocalDate.now(TestClock.INSTANCE);
        json(
                postJson(
                        "/api/v1/recurring",
                        Map.of(
                                "name",
                                "Rent",
                                "type",
                                "EXPENSE",
                                "accountId",
                                card,
                                "amount",
                                15000,
                                "frequency",
                                "MONTHLY",
                                "startDate",
                                today.plusDays(3).toString()),
                        auth),
                201);

        message("/planned");

        assertThat(lastText())
                .contains("Planned for 30 days")
                .contains("Rent — −15,000.00 ₴")
                .contains("Expenses");
    }

    @Test
    void todayAndMonthCommands() throws Exception {
        link();
        message("150 кава");

        message("/today");
        assertThat(lastText()).contains("−150.00 ₴").contains("Їжа — 150 ₴");

        message("/month");
        assertThat(lastText()).contains("Spending — 150 ₴").contains("Income — 0 ₴");
    }

    @Test
    void webhookNeedsTheSecret() throws Exception {
        mockMvc.perform(post(TelegramWebhookController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TelegramWebhookController.PATH)
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlinkInTheWebClientSaysGoodbye() throws Exception {
        link();
        clearInvocations(client);

        mockMvc.perform(delete("/api/v1/telegram").header("Authorization", auth))
                .andExpect(status().isNoContent());

        verify(client).sendMessage(eq(CHAT), anyString(), isNull(), isNull());
        assertThat(lastText()).contains("disconnected");
        message("150 кава");
        assertThat(count("select count(*) from transactions")).isZero();
    }

    @Test
    void settingsChooseTheQuickEntryAccount() throws Exception {
        link();
        json(putJson("/api/v1/telegram/settings", Map.of("quickEntryAccountId", cash), auth), 200);

        message("150 кава");

        assertThat(((Number) onlyTransaction().get("accountId")).longValue()).isEqualTo(cash);
        verify(client, never()).sendPhoto(anyLong(), any(), anyString(), any());
    }

    // helpers

    private void link() throws Exception {
        message("/start " + code());
    }

    private String code() throws Exception {
        String url = (String) json(postJson("/api/v1/telegram/link", Map.of("password", PASSWORD), auth), 200)
                .get("url");
        return url.substring(url.indexOf('=') + 1);
    }

    /** Sends a message to the bot by the webhook; returns the id of the reply. */
    private long message(String text) throws Exception {
        long id = messageIds.incrementAndGet();
        Map<String, Object> message = Map.of(
                "message_id",
                id,
                "from",
                Map.of("id", 42, "first_name", "Olena", "username", "olena", "language_code", "en"),
                "chat",
                Map.of("id", CHAT, "type", "private"),
                "text",
                text);
        webhook(Map.of("update_id", updateIds.incrementAndGet(), "message", message));
        return messageIds.get();
    }

    private void callback(long messageId, String data, String repliedText) throws Exception {
        Map<String, Object> message = new HashMap<>(
                Map.of("message_id", messageId, "chat", Map.of("id", CHAT, "type", "private"), "text", "…"));
        if (repliedText != null) {
            message.put(
                    "reply_to_message",
                    Map.of("message_id", 1, "chat", Map.of("id", CHAT, "type", "private"), "text", repliedText));
        }
        Map<String, Object> query =
                Map.of("id", "q" + messageId, "from", Map.of("id", 42), "message", message, "data", data);
        webhook(Map.of("update_id", updateIds.incrementAndGet(), "callback_query", query));
    }

    private void webhook(Map<String, Object> update) throws Exception {
        mockMvc.perform(post(TelegramWebhookController.PATH)
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(update)))
                .andExpect(status().isOk());
    }

    private String lastText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).sendMessage(eq(CHAT), text.capture(), any(), any());
        return text.getValue();
    }

    private Keyboard lastKeyboard() {
        ArgumentCaptor<Keyboard> keyboard = ArgumentCaptor.forClass(Keyboard.class);
        verify(client, atLeastOnce()).sendMessage(eq(CHAT), anyString(), keyboard.capture(), any());
        return keyboard.getValue();
    }

    private String editedText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).editMessageText(eq(CHAT), anyLong(), text.capture(), any());
        return text.getValue();
    }

    private Keyboard editedKeyboard() {
        ArgumentCaptor<Keyboard> keyboard = ArgumentCaptor.forClass(Keyboard.class);
        verify(client, atLeastOnce()).editMessageText(eq(CHAT), anyLong(), anyString(), keyboard.capture());
        return keyboard.getValue();
    }

    private Map<String, Object> onlyTransaction() {
        return jdbc.queryForMap("""
                select t.id, t.type, t.comment, t.account_id as "accountId", t.to_account_id,
                       (select s.category_id from transaction_splits s where s.transaction_id = t.id) as category_id
                from transactions t""");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void jdbcUpdate(String sql) {
        jdbc.update(sql);
    }

    private Long account(String name, String type, String currency) throws Exception {
        return createAccount(auth, name, type, currency, 1000);
    }

    private Long category(String name, String kind, Long parentId) throws Exception {
        return createCategory(auth, name, kind, parentId);
    }
}
