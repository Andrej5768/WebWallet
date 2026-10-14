package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

/**
 * Blocks a client IP after too many failed logins for the configured duration.
 */
@Service
public class LoginAttemptService {

    private final int maxFailedLogins;
    private final Cache<String, Integer> failedAttempts;

    public LoginAttemptService(AppProperties properties) {
        this.maxFailedLogins = properties.auth().maxFailedLogins();
        this.failedAttempts = Caffeine.newBuilder()
                .expireAfterWrite(properties.auth().loginBlockDuration())
                .maximumSize(100_000)
                .build();
    }

    public void loginFailed(String ip) {
        failedAttempts.asMap().merge(ip, 1, Integer::sum);
    }

    public void loginSucceeded(String ip) {
        failedAttempts.invalidate(ip);
    }

    /** Forgets all failed attempts. */
    public void reset() {
        failedAttempts.invalidateAll();
    }

    public boolean isBlocked(String ip) {
        Integer attempts = failedAttempts.getIfPresent(ip);
        return attempts != null && attempts >= maxFailedLogins;
    }
}
