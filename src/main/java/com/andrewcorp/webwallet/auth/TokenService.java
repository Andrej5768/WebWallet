package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.token.RefreshToken;
import com.andrewcorp.webwallet.auth.token.RefreshTokenRepository;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues JWT access tokens and rotating refresh tokens.
 */
@Service
public class TokenService {

    public static final String ROLES_CLAIM = "roles";
    /** {@link User#getTokenVersion()} at the time the access token was issued. */
    public static final String TOKEN_VERSION_CLAIM = "ver";

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokens;
    private final AppProperties.Jwt properties;
    private final Clock clock;

    public TokenService(
            JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokens, AppProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokens = refreshTokens;
        this.properties = properties.jwt();
        this.clock = clock;
    }

    /** Starts a new session (refresh token family) for the user. */
    @Transactional
    public TokenResponse issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for a new token pair. Presenting an already rotated token is treated as theft
     * and revokes the whole session.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens
                .findByTokenHash(TokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        User user = token.getUser();
        if (token.isExpired(now) || !user.isEnabled()) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        // Conditional update, so that of two concurrent requests with the same token only one succeeds
        if (refreshTokens.revokeIfActive(token.getId(), now) == 0) {
            log.warn("Reuse of a revoked refresh token detected for user {}, revoking the session", user.getId());
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return issueTokens(user, token.getFamilyId());
    }

    /** Ends the session the refresh token belongs to. Unknown tokens are ignored. */
    @Transactional
    public void revokeSession(String rawRefreshToken) {
        refreshTokens
                .findByTokenHash(TokenHasher.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** Ends all sessions of the user, including the access tokens already issued. */
    @Transactional
    public void revokeAllSessions(User user) {
        user.invalidateTokens();
        refreshTokens.revokeAllForUser(user, clock.instant());
    }

    private TokenResponse issueTokens(User user, UUID familyId) {
        Instant now = clock.instant();
        String rawRefreshToken = TokenHasher.newToken();
        refreshTokens.save(new RefreshToken(
                user, familyId, TokenHasher.hash(rawRefreshToken), now.plus(properties.refreshTokenTtl())));
        return TokenResponse.bearer(
                createAccessToken(user, now),
                rawRefreshToken,
                properties.accessTokenTtl().toSeconds());
    }

    private String createAccessToken(User user, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
