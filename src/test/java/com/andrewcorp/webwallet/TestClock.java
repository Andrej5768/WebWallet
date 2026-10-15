package com.andrewcorp.webwallet;

import com.andrewcorp.webwallet.common.util.Zones;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The clock of the application in tests: the system clock, which a test can move to another moment. It keeps running
 * from there, so that timeouts and expiry still work.
 */
public final class TestClock extends Clock {

    public static final TestClock INSTANCE = new TestClock();

    private volatile Duration offset = Duration.ZERO;

    private TestClock() {}

    /** Moves the clock, so that it now shows the moment. */
    public void set(Instant now) {
        offset = Duration.between(Instant.now(), now);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return Zones.KYIV;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Clock self = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return self.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }
        };
    }
}
