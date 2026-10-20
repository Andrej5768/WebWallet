package com.andrewcorp.webwallet.auth.token;

public enum UserTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** Issued after the password when two-factor authentication is on; exchanged for a session with the code. */
    TWO_FACTOR_LOGIN,
    /** Hands a Google sign-in from the callback redirect to the web client, which exchanges it for a session. */
    GOOGLE_LOGIN
}
