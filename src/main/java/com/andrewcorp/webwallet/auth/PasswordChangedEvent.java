package com.andrewcorp.webwallet.auth;

/**
 * The password of the user was changed or reset. Access that does not go through the password, such as a connected
 * Telegram chat, ends with it, so that whoever took over the account keeps nothing.
 */
public record PasswordChangedEvent(Long userId) {}
