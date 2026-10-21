package com.andrewcorp.webwallet.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** The parts of the Telegram Bot API objects (https://core.telegram.org/bots/api) the bot reads. */
public final class TelegramApi {

    private TelegramApi() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response<T>(boolean ok, T result, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(
            @JsonProperty("update_id") long updateId,
            Message message,
            @JsonProperty("callback_query") CallbackQuery callbackQuery) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            @JsonProperty("message_id") long messageId,
            From from,
            Chat chat,
            String text,
            @JsonProperty("reply_to_message") Message replyToMessage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record From(
            long id,
            @JsonProperty("first_name") String firstName,
            String username,
            @JsonProperty("language_code") String languageCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type) {

        public boolean isPrivate() {
            return "private".equals(type);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, From from, Message message, String data) {}

    /** A button under a message: it either sends {@code callbackData} back to the bot or opens {@code url}. */
    public record Button(String text, String callbackData, String url) {

        public static Button callback(String text, String data) {
            return new Button(text, data, null);
        }

        public static Button link(String text, String url) {
            return new Button(text, null, url);
        }

        Map<String, String> json() {
            return url != null ? Map.of("text", text, "url", url) : Map.of("text", text, "callback_data", callbackData);
        }
    }

    /** Rows of buttons; an empty keyboard removes the buttons of an edited message. */
    public record Keyboard(List<List<Button>> rows) {

        public static final Keyboard NONE = new Keyboard(List.of());

        public static Keyboard of(List<List<Button>> rows) {
            return new Keyboard(rows.stream().filter(row -> !row.isEmpty()).toList());
        }

        Map<String, Object> json() {
            return Map.of(
                    "inline_keyboard",
                    rows.stream()
                            .map(row -> row.stream().map(Button::json).toList())
                            .toList());
        }
    }
}
