package com.andrewcorp.webwallet.bank;

/** When a bank is asked for new operations. */
public enum SyncMode {
    /** Every 15 minutes within a daily time window and once at night. */
    INTERVAL,
    /** Once a day. */
    DAILY,
    /** The bank pushes new operations; the accounts are also checked once a day. */
    WEBHOOK,
    /** Only when the user asks. */
    MANUAL
}
