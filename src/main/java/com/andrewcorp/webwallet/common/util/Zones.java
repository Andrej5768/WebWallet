package com.andrewcorp.webwallet.common.util;

import java.time.ZoneId;

public final class Zones {

    /** Dates of operations, months of the budget and reports, and "today" are those of Ukraine. */
    public static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private Zones() {}
}
