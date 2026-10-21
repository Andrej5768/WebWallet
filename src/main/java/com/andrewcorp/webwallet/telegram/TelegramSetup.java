package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.telegram.TelegramApi.Update;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sets up the bot when the application starts: the command menu, and the webhook in production. Without a webhook
 * secret it polls Telegram for updates in a thread of its own instead (local development).
 */
@Component
class TelegramSetup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramSetup.class);
    private static final List<String> COMMANDS = List.of("balance", "today", "month", "planned", "help", "stop");
    private static final int POLL_TIMEOUT_SECONDS = 25;
    private static final long RETRY_MILLIS = 5_000;

    private final TelegramClient client;
    private final TelegramBot bot;
    private final BotTexts texts;
    private final AppProperties properties;
    private final String publicUrl;
    private volatile Thread poller;

    TelegramSetup(
            TelegramClient client,
            TelegramBot bot,
            BotTexts texts,
            AppProperties properties,
            @Value("${app.public-url:}") String publicUrl) {
        this.client = client;
        this.bot = bot;
        this.texts = texts;
        this.properties = properties;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void configure() {
        if (!properties.telegram().enabled()) {
            return;
        }
        try {
            client.setMyCommands(commands(Locale.ENGLISH), "");
            client.setMyCommands(commands(BotTexts.locale("uk")), "uk");
            if (properties.telegram().webhook()) {
                if (publicUrl.isEmpty()) {
                    log.warn("The Telegram webhook needs app.public-url; the bot gets no updates");
                } else {
                    client.setWebhook(
                            publicUrl + TelegramWebhookController.PATH,
                            properties.telegram().webhookSecret());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Telegram bot setup failed: {}", e.getMessage());
        }
    }

    private Map<String, String> commands(Locale locale) {
        Map<String, String> commands = new LinkedHashMap<>();
        COMMANDS.forEach(command -> commands.put(command, texts.get(locale, "bot.command." + command)));
        return commands;
    }

    @Override
    public void start() {
        if (properties.telegram().enabled() && !properties.telegram().webhook()) {
            poller = Thread.ofPlatform().name("telegram-polling").daemon().start(this::poll);
        }
    }

    @Override
    public void stop() {
        Thread thread = poller;
        poller = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return poller != null;
    }

    private void poll() {
        long offset = 0;
        boolean webhookDeleted = false;
        while (poller == Thread.currentThread()) {
            try {
                if (!webhookDeleted) {
                    client.deleteWebhook();
                    webhookDeleted = true;
                }
                for (Update update : client.getUpdates(offset, POLL_TIMEOUT_SECONDS)) {
                    offset = update.updateId() + 1;
                    bot.handle(update);
                }
            } catch (RuntimeException e) {
                if (poller != Thread.currentThread()) {
                    return;
                }
                log.warn("Telegram polling failed: {}", e.getMessage());
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }
}
