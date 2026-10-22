package com.andrewcorp.webwallet.auth.dto;

import com.andrewcorp.webwallet.auth.IssuedTokens;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Either a session, like {@link TokenResponse}, or, when two-factor authentication is on, a challenge to exchange
 * with a code at {@code POST /api/v1/auth/login/two-factor}. The web client gets the refresh token in an HttpOnly
 * cookie; the native apps get it in {@code refreshToken}.
 *
 * @param expiresIn lifetime of the access token in seconds
 * @param twoFactorChallenge set instead of the tokens when the code is needed
 * @param refreshToken for the native apps; null for the web client and with a challenge
 */
public record LoginResponse(
        @Schema(nullable = true) String accessToken,
        @Schema(nullable = true) String tokenType,
        @Schema(nullable = true) Long expiresIn,
        @Schema(nullable = true) String twoFactorChallenge,
        @Schema(nullable = true) String refreshToken) {

    /** The session, with the refresh token in the body when the client keeps it itself. */
    public static LoginResponse session(IssuedTokens tokens, boolean includeRefresh) {
        return new LoginResponse(
                tokens.accessToken(),
                "Bearer",
                tokens.expiresIn(),
                null,
                includeRefresh ? tokens.refreshToken() : null);
    }

    public static LoginResponse challenge(String challenge) {
        return new LoginResponse(null, null, null, challenge, null);
    }

    /** No session: the request did what it had to without signing in. */
    public static LoginResponse none() {
        return new LoginResponse(null, null, null, null, null);
    }
}
