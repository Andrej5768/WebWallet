package com.andrewcorp.webwallet.auth.token;

public enum UserTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** Issued after the password when two-factor authentication is on; exchanged for a session with the code. */
    TWO_FACTOR_LOGIN
}
