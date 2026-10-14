package com.andrewcorp.webwallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.token.RefreshToken;
import com.andrewcorp.webwallet.auth.token.RefreshTokenRepository;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TokenCleanupTaskTest extends AbstractIntegrationTest {

    @Autowired
    private TokenCleanupTask task;

    @Autowired
    private UserTokenRepository userTokens;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Test
    void purgeRemovesOnlyStaleTokens() {
        User user = createVerifiedUser();
        Instant now = Instant.now();
        Instant longAgo = now.minus(Duration.ofDays(3));

        userTokens.save(new UserToken(user, UserTokenType.PASSWORD_RESET, TokenHasher.hash("expired"), longAgo));
        UserToken used = new UserToken(
                user, UserTokenType.EMAIL_VERIFICATION, TokenHasher.hash("used"), now.plus(Duration.ofDays(1)));
        used.markUsed(longAgo);
        userTokens.save(used);
        userTokens.save(new UserToken(
                user, UserTokenType.PASSWORD_RESET, TokenHasher.hash("valid"), now.plus(Duration.ofHours(1))));

        refreshTokens.save(new RefreshToken(user, UUID.randomUUID(), TokenHasher.hash("old"), longAgo));
        RefreshToken revoked =
                new RefreshToken(user, UUID.randomUUID(), TokenHasher.hash("revoked"), now.plus(Duration.ofDays(10)));
        revoked.revoke(now);
        refreshTokens.save(revoked);

        task.purge();

        assertThat(userTokens.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.getUsedAt()).isNull());
        // Revoked but unexpired tokens are kept to detect reuse
        assertThat(refreshTokens.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.isRevoked()).isTrue());
    }
}
