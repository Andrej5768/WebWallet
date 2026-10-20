package com.andrewcorp.webwallet.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Either a session, like {@link TokenResponse}, or, when two-factor authentication is on, a challenge to exchange
 * with a code at {@code POST /api/v1/auth/login/two-factor}. The refresh token is set in an HttpOnly cookie.
 *
 * @param expiresIn lifetime of the access token in seconds
 * @param twoFactorChallenge set instead of the tokens when the code is needed
 */
public record LoginResponse(
        @Schema(nullable = true) String accessToken,
        @Schema(nullable = true) String tokenType,
        @Schema(nullable = true) Long expiresIn,
        @Schema(nullable = true) String twoFactorChallenge) {

    public static LoginResponse session(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, null);
    }

    public static LoginResponse challenge(String challenge) {
        return new LoginResponse(null, null, null, challenge);
    }
}
