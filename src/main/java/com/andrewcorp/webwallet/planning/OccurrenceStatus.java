package com.andrewcorp.webwallet.planning;

public enum OccurrenceStatus {
    /** Due and neither paid nor skipped yet. */
    PENDING,
    /** A transaction stands for it: matched automatically, linked or confirmed by the user, or created automatically. */
    PAID,
    SKIPPED
}
