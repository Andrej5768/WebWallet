package com.andrewcorp.webwallet.common.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;

/**
 * Counts attempts per key within a fixed window that starts with the first attempt.
 */
public final class AttemptCounter {

    private final int limit;
    private final Cache<String, Integer> attempts;

    public AttemptCounter(int limit, Duration window) {
        this.limit = limit;
        this.attempts = Caffeine.newBuilder()
                .expireAfter(Expiry.creating((String key, Integer count) -> window))
                .maximumSize(100_000)
                .build();
    }

    public boolean isExceeded(String key) {
        Integer count = attempts.getIfPresent(key);
        return count != null && count >= limit;
    }

    public void record(String key) {
        attempts.asMap().merge(key, 1, Integer::sum);
    }

    public void reset(String key) {
        attempts.invalidate(key);
    }

    public void resetAll() {
        attempts.invalidateAll();
    }
}
