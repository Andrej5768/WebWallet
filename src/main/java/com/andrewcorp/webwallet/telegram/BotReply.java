package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;

/** A message of the bot: HTML text with buttons under it. */
public record BotReply(String html, Keyboard keyboard) {

    public static BotReply text(String html) {
        return new BotReply(html, Keyboard.NONE);
    }
}
