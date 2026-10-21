package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.auth.PasswordChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A new password disconnects the Telegram chat: it may be the chat of whoever knew the old one. */
@Component
class TelegramPasswordListener {

    private final TelegramLinkService linkService;
    private final TelegramBot bot;

    TelegramPasswordListener(TelegramLinkService linkService, TelegramBot bot) {
        this.linkService = linkService;
        this.bot = bot;
    }

    @EventListener
    void onPasswordChanged(PasswordChangedEvent event) {
        linkService.unlink(event.userId()).ifPresent(chatId -> bot.sayUnlinked(chatId, event.userId()));
    }
}
