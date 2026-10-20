package com.andrewcorp.webwallet.auth.token;

public enum UserTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /** Issued after the password when two-factor authentication is on; exchanged for a session with the code. */
    TWO_FACTOR_LOGIN,
    /** Hands a Google sign-in from the callback redirect to the web client, which exchanges it for a session. */
    GOOGLE_LOGIN,
    /** Sent by email when the user asks to delete the account; cancels the deletion. */
    ACCOUNT_RESTORE,
    /** Given at a sign-in during the deletion period, after the password and the code; cancels and signs in. */
    ACCOUNT_RESTORE_SESSION
}
