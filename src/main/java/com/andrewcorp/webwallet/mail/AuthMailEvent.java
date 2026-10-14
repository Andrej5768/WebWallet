package com.andrewcorp.webwallet.mail;

import java.util.Locale;

/**
 * Published inside the transaction that created the token; the mail is sent after the transaction commits.
 */
public record AuthMailEvent(Type type, String email, String firstName, Locale locale, String token) {

    public enum Type {
        EMAIL_VERIFICATION,
        PASSWORD_RESET
    }
}
