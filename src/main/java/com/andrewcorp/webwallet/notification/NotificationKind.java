package com.andrewcorp.webwallet.notification;

public enum NotificationKind {
    /** A payment of a recurring payment due tomorrow or today and not yet linked to a transaction. */
    RECURRING,
    /** The spending of an envelope reached 80 % or 100 % of the month's budget. */
    BUDGETS,
    /** A payment or a declaration of the sole proprietor due in 7 days or tomorrow. */
    TAXES,
    /** The bank no longer accepts the token of a connection. */
    BANKS,
    /** The balance forecast goes below zero within 30 days. */
    FORECAST,
    /** A goal reached its target. */
    GOALS,
    /** Monday: the spending of the month so far against the same days of the previous month. */
    WEEKLY,
    /** The 1st: the previous month against the month before it. */
    MONTHLY,
    /** The weekly report by email. */
    EMAIL_WEEKLY,
    /** The monthly report by email. */
    EMAIL_MONTHLY
}
