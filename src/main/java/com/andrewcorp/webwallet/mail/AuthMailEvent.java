package com.andrewcorp.webwallet.mail;

import java.time.Instant;
import java.util.Locale;

/**
 * Published inside the transaction that created the token; the mail is sent after the transaction commits. The
 * mail contains nothing entered at registration, since the address may belong to someone else.
 *
 * @param token the one-time token for the link, {@code null} for {@link Type#ACCOUNT_EXISTS}
 * @param at when the account is deleted, for {@link Type#ACCOUNT_DELETION}
 */
public record AuthMailEvent(Type type, String email, Locale locale, String token, Instant at) {

    public AuthMailEvent(Type type, String email, Locale locale, String token) {
        this(type, email, locale, token, null);
    }

    public enum Type {
        EMAIL_VERIFICATION,
        PASSWORD_RESET,
        /** Someone tried to register with the address of an existing account. */
        ACCOUNT_EXISTS,
        /** The user asked to delete the account; the link cancels it. */
        ACCOUNT_DELETION
    }
}
