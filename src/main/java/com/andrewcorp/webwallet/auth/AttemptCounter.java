package com.andrewcorp.webwallet.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;

/**
 * Counts attempts per key within a fixed window that starts with the first attempt.
 */
final class AttemptCounter {

    private final int limit;
    private final Cache<String, Integer> attempts;

    AttemptCounter(int limit, Duration window) {
        this.limit = limit;
        this.attempts = Caffeine.newBuilder()
                .expireAfter(Expiry.creating((String key, Integer count) -> window))
                .maximumSize(100_000)
                .build();
    }

    boolean isExceeded(String key) {
        Integer count = attempts.getIfPresent(key);
        return count != null && count >= limit;
    }

    void record(String key) {
        attempts.asMap().merge(key, 1, Integer::sum);
    }

    void reset(String key) {
        attempts.invalidate(key);
    }

    void resetAll() {
        attempts.invalidateAll();
    }
}
