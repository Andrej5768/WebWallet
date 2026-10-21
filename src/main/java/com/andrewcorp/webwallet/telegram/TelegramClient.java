package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.telegram.TelegramApi.Message;
import com.andrewcorp.webwallet.telegram.TelegramApi.Response;
import com.andrewcorp.webwallet.telegram.TelegramApi.Update;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client of the Telegram Bot API. Texts are HTML (parse mode {@code HTML}), so the callers escape user input with
 * {@link BotTexts#escape}.
 */
@Component
public class TelegramClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public TelegramClient(
            RestClient.Builder builder,
            AppProperties properties,
            JsonMapper jsonMapper,
            @Value("${app.telegram.base-url:https://api.telegram.org}") String baseUrl) {
        this.jsonMapper = jsonMapper;
        this.restClient = builder.baseUrl(
                        baseUrl + "/bot" + properties.telegram().botToken())
                .build();
    }

    /** Waits up to {@code timeoutSeconds} for updates after the last one handled. */
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        Response<List<Update>> response = restClient
                .post()
                .uri("/getUpdates")
                .body(Map.of(
                        "offset", offset,
                        "timeout", timeoutSeconds,
                        "allowed_updates", List.of("message", "callback_query")))
                .retrieve()
                .body(new ParameterizedTypeReference<Response<List<Update>>>() {});
        return response != null && response.result() != null ? response.result() : List.of();
    }

    /** @param replyTo the message the new one answers, or null */
    public Message sendMessage(long chatId, String html, Keyboard keyboard, Long replyTo) {
        Map<String, Object> body = textBody(chatId, html, keyboard);
        if (replyTo != null) {
            body.put("reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
        }
        Response<Message> response = restClient
                .post()
                .uri("/sendMessage")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Response<Message>>() {});
        return response != null ? response.result() : null;
    }

    /** Replaces the text and the buttons of a message of the bot; an unchanged message is not an error. */
    public void editMessageText(long chatId, long messageId, String html, Keyboard keyboard) {
        Map<String, Object> body = textBody(chatId, html, keyboard);
        body.put("message_id", messageId);
        try {
            restClient.post().uri("/editMessageText").body(body).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.BadRequest e) {
            if (!e.getResponseBodyAsString().contains("message is not modified")) {
                throw e;
            }
        }
    }

    /** Sends a PNG image with the text under it (at most 1024 characters). */
    public void sendPhoto(long chatId, byte[] png, String html, Keyboard keyboard) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("chat_id", String.valueOf(chatId));
        parts.add("photo", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "chart.png";
            }
        });
        parts.add("caption", html);
        parts.add("parse_mode", "HTML");
        if (keyboard != null && !keyboard.rows().isEmpty()) {
            // A multipart field is a string, so the keyboard goes there as JSON text
            parts.add("reply_markup", jsonMapper.writeValueAsString(keyboard.json()));
        }
        restClient
                .post()
                .uri("/sendPhoto")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();
    }

    /** Stops the spinner on the pressed button, optionally with a short notice. */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) {
            body.put("text", text);
        }
        restClient.post().uri("/answerCallbackQuery").body(body).retrieve().toBodilessEntity();
    }

    public void setWebhook(String url, String secret) {
        restClient
                .post()
                .uri("/setWebhook")
                .body(Map.of(
                        "url", url,
                        "secret_token", secret,
                        "allowed_updates", List.of("message", "callback_query")))
                .retrieve()
                .toBodilessEntity();
    }

    /** Needed before polling: Telegram does not give updates by polling while a webhook is set. */
    public void deleteWebhook() {
        restClient.post().uri("/deleteWebhook").body(Map.of()).retrieve().toBodilessEntity();
    }

    /** The command menu of the bot; {@code languageCode} empty for the default one. */
    public void setMyCommands(Map<String, String> commands, String languageCode) {
        Map<String, Object> body = new HashMap<>();
        body.put(
                "commands",
                commands.entrySet().stream()
                        .map(command -> Map.of("command", command.getKey(), "description", command.getValue()))
                        .toList());
        if (!languageCode.isEmpty()) {
            body.put("language_code", languageCode);
        }
        restClient.post().uri("/setMyCommands").body(body).retrieve().toBodilessEntity();
    }

    private static Map<String, Object> textBody(long chatId, String html, Keyboard keyboard) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (keyboard != null) {
            body.put("reply_markup", keyboard.json());
        }
        return body;
    }
}
