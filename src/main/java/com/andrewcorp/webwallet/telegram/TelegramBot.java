package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.telegram.TelegramApi.CallbackQuery;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.telegram.TelegramApi.Message;
import com.andrewcorp.webwallet.telegram.TelegramApi.Update;
import com.andrewcorp.webwallet.telegram.TelegramLinkService.LinkResult;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles the updates of the bot: commands, operations written to it and the buttons under its messages. The
 * messages of a chat that is not connected only get the way to connect it.
 */
@Component
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private final TelegramClient client;
    private final TelegramLinkService linkService;
    private final QuickEntryService quickEntry;
    private final BotCommands commands;
    private final TelegramNotifications notifications;
    private final BotTexts texts;
    private final UserRepository users;

    public TelegramBot(
            TelegramClient client,
            TelegramLinkService linkService,
            QuickEntryService quickEntry,
            BotCommands commands,
            TelegramNotifications notifications,
            BotTexts texts,
            UserRepository users) {
        this.client = client;
        this.linkService = linkService;
        this.quickEntry = quickEntry;
        this.commands = commands;
        this.notifications = notifications;
        this.texts = texts;
        this.users = users;
    }

    /** Never throws: a failed update is logged and answered with an apology, so that Telegram does not resend it. */
    public void handle(Update update) {
        if (update.callbackQuery() != null && update.callbackQuery().message() != null) {
            CallbackQuery query = update.callbackQuery();
            run(query.message().chat().id(), null, () -> onCallback(query));
        } else if (update.message() != null && update.message().text() != null) {
            Message message = update.message();
            run(
                    message.chat().id(),
                    message.from() != null ? message.from().languageCode() : null,
                    () -> onMessage(message));
        }
    }

    /** Says goodbye in a chat disconnected in the web client. */
    public void sayUnlinked(long chatId, Long userId) {
        try {
            client.sendMessage(chatId, texts.get(locale(userId), "bot.unlinked"), null, null);
        } catch (RuntimeException e) {
            log.warn("Telegram goodbye was not sent: {}", e.getClass().getSimpleName());
        }
    }

    private void onMessage(Message message) {
        long chatId = message.chat().id();
        Optional<TelegramLink> link = linkService.byChat(chatId);
        Locale locale = link.map(found -> locale(found.getUserId()))
                .orElseGet(() ->
                        BotTexts.locale(message.from() != null ? message.from().languageCode() : null));
        if (!message.chat().isPrivate()) {
            send(chatId, texts.get(locale, "bot.privateOnly"));
            return;
        }
        String text = message.text().trim();
        if (text.startsWith("/")) {
            command(message, text, link, locale);
            return;
        }
        if (link.isEmpty()) {
            send(chatId, texts.get(locale, "bot.notLinked"));
            return;
        }
        BotReply reply = quickEntry.record(link.get(), locale, text, List.of());
        client.sendMessage(chatId, reply.html(), reply.keyboard(), message.messageId());
    }

    private void command(Message message, String text, Optional<TelegramLink> link, Locale locale) {
        long chatId = message.chat().id();
        String[] parts = text.split("\\s+", 2);
        // In a group a command may name the bot: /balance@WebWalletBot
        String command = parts[0].substring(1).replaceFirst("@.*$", "").toLowerCase(Locale.ROOT);
        if (command.equals("start")) {
            start(message, parts.length > 1 ? parts[1].trim() : null, link, locale);
            return;
        }
        if (command.equals("stop")) {
            send(chatId, texts.get(locale, linkService.unlinkChat(chatId) ? "bot.unlinked" : "bot.notLinked"));
            return;
        }
        if (link.isEmpty()) {
            send(chatId, texts.get(locale, "bot.notLinked"));
            return;
        }
        Long userId = link.get().getUserId();
        String reply = switch (command) {
            case "balance" -> commands.balance(userId, locale);
            case "today" -> commands.today(userId, locale);
            case "month" -> commands.month(userId, locale);
            case "planned" -> commands.planned(userId, locale);
            default -> texts.get(locale, "bot.help");
        };
        send(chatId, reply);
    }

    /** /start with the code of a t.me link connects the chat; without one it explains how to connect. */
    private void start(Message message, String code, Optional<TelegramLink> link, Locale locale) {
        long chatId = message.chat().id();
        if (code == null || code.isEmpty()) {
            send(chatId, texts.get(locale, link.isPresent() ? "bot.help" : "bot.notLinked"));
            return;
        }
        LinkResult result = linkService.link(code, chatId, message.from());
        switch (result) {
            case LINKED -> {
                Long userId = linkService.byChat(chatId).orElseThrow().getUserId();
                Locale userLocale = locale(userId);
                String email = users.findById(userId).map(User::getEmail).orElse("");
                send(
                        chatId,
                        texts.get(userLocale, "bot.welcome", BotTexts.escape(email)) + "\n\n"
                                + texts.get(userLocale, "bot.help"));
            }
            case TAKEN -> send(chatId, texts.get(locale, "bot.alreadyLinked"));
            case INVALID -> send(chatId, texts.get(locale, "bot.linkInvalid"));
        }
    }

    /**
     * Buttons: {@code pl} sends the planned payments, {@code rp:<recurring id>:<date>} records a reminded payment,
     * {@code pk:<account id>…} repeats the operation of the message the bot answered with the accounts
     * chosen so far, {@code pk-} drops it; any other data is an action on a recorded operation, see
     * {@link QuickEntryService#action}.
     */
    private void onCallback(CallbackQuery query) {
        long chatId = query.message().chat().id();
        long messageId = query.message().messageId();
        Optional<TelegramLink> link = linkService.byChat(chatId);
        if (link.isEmpty()) {
            client.answerCallbackQuery(query.id(), texts.get(BotTexts.locale(null), "bot.notLinked"));
            return;
        }
        Locale locale = locale(link.get().getUserId());
        String data = query.data() != null ? query.data() : "";
        String notice = null;
        if (data.equals("pl")) {
            send(chatId, notifications.planned(link.get().getUserId(), locale));
        } else if (data.startsWith("rp:")) {
            String[] parts = data.split(":");
            if (parts.length == 3) {
                edit(
                        chatId,
                        messageId,
                        notifications.markPaid(
                                link.get().getUserId(), locale, Long.parseLong(parts[1]), LocalDate.parse(parts[2])));
            }
        } else if (data.equals("pk-")) {
            edit(chatId, messageId, BotReply.text(texts.get(locale, "bot.entry.cancelled")));
        } else if (data.startsWith("pk:")) {
            Message original = query.message().replyToMessage();
            if (original != null && original.text() != null) {
                edit(chatId, messageId, quickEntry.record(link.get(), locale, original.text(), ids(data.substring(3))));
            } else {
                edit(chatId, messageId, BotReply.text(texts.get(locale, "bot.entry.expired")));
            }
        } else {
            String[] parts = data.split(":");
            List<Long> ids = parts.length > 1 ? ids(data.substring(parts[0].length() + 1)) : List.of();
            if (!ids.isEmpty()) {
                QuickEntryService.Outcome outcome = quickEntry.action(
                        link.get().getUserId(), locale, parts[0], ids.get(0), ids.size() > 1 ? ids.get(1) : null);
                edit(chatId, messageId, outcome.reply());
                notice = outcome.notice();
            }
        }
        client.answerCallbackQuery(query.id(), notice);
    }

    private void run(long chatId, String languageCode, Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException e) {
            log.error("Telegram update failed", e);
            try {
                Locale locale = linkService
                        .byChat(chatId)
                        .map(link -> locale(link.getUserId()))
                        .orElseGet(() -> BotTexts.locale(languageCode));
                send(chatId, texts.get(locale, "bot.error"));
            } catch (RuntimeException ignored) {
                // Telegram itself may be what failed
            }
        }
    }

    private void send(long chatId, String html) {
        client.sendMessage(chatId, html, null, null);
    }

    private void edit(long chatId, long messageId, BotReply reply) {
        client.editMessageText(
                chatId, messageId, reply.html(), reply.keyboard() != null ? reply.keyboard() : Keyboard.NONE);
    }

    private Locale locale(Long userId) {
        return BotTexts.locale(users.findById(userId).map(User::getLocale).orElse(null));
    }

    /** Ids separated by colons; an id that is not a number ends the list. */
    private static List<Long> ids(String text) {
        List<Long> ids = new ArrayList<>();
        for (String part : text.split(":")) {
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return ids;
    }
}
