package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.token.RefreshTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes expired and used tokens. Revoked refresh tokens are kept until they expire, so that reuse of a stolen
 * token can still be detected.
 */
@Component
public class TokenCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupTask.class);
    private static final Duration RETENTION = Duration.ofDays(1);

    private final UserTokenRepository userTokens;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public TokenCleanupTask(UserTokenRepository userTokens, RefreshTokenRepository refreshTokens, Clock clock) {
        this.userTokens = userTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purge() {
        Instant before = clock.instant().minus(RETENTION);
        int removedUserTokens = userTokens.deleteStale(before);
        int removedRefreshTokens = refreshTokens.deleteExpired(before);
        log.info("Removed {} stale email tokens and {} expired refresh tokens", removedUserTokens, removedRefreshTokens);
    }
}
