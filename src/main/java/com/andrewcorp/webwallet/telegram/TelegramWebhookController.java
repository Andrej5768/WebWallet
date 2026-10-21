package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.telegram.TelegramApi.Update;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Telegram posts the updates of the bot here, with the secret of the webhook in a header. */
@Hidden
@RestController
public class TelegramWebhookController {

    public static final String PATH = "/api/v1/telegram/webhook";

    private final TelegramBot bot;
    private final AppProperties properties;

    public TelegramWebhookController(TelegramBot bot, AppProperties properties) {
        this.bot = bot;
        this.properties = properties;
    }

    /** Answers 200 for every update it accepts, even a failed one: Telegram would resend it again and again. */
    @PostMapping(PATH)
    public ResponseEntity<Void> update(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody Update update) {
        if (!properties.telegram().webhook() || secret == null || !matches(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        bot.handle(update);
        return ResponseEntity.ok().build();
    }

    private boolean matches(String secret) {
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                properties.telegram().webhookSecret().getBytes(StandardCharsets.UTF_8));
    }
}
