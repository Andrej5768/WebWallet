package com.andrewcorp.webwallet.bank;

/** How WebWallet reaches the data of a user at the bank. */
public enum BankAuthMode {
    /** With the personal token the user copied from the bank. */
    TOKEN,
    /**
     * As a registered service provider, with the id of the access request the user approved in the bank app; the
     * requests are signed with the key of the service.
     */
    PROVIDER
}
