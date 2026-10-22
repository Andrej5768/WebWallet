package com.andrewcorp.webwallet.auth.dto;

import com.andrewcorp.webwallet.auth.IssuedTokens;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A session. {@code refreshToken} is set for the native apps only; the web client gets it in an HttpOnly cookie.
 *
 * @param expiresIn lifetime of the access token in seconds
 * @param refreshToken for the native apps; null for the web client, which has it in a cookie
 * @param trustedDeviceToken for the native apps after a two-factor sign-in with trustDevice, to skip the code next
 *     time; null everywhere else (the web client gets a cookie)
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        @Schema(nullable = true) String refreshToken,
        @Schema(nullable = true) String trustedDeviceToken) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, null, null);
    }

    /** The session, with the refresh token in the body when the client keeps it itself. */
    public static TokenResponse bearer(IssuedTokens tokens, boolean includeRefresh) {
        return bearer(tokens, includeRefresh, null);
    }

    /** The session with the refresh token when the client keeps it itself, and a trusted-device token to keep. */
    public static TokenResponse bearer(IssuedTokens tokens, boolean includeRefresh, String trustedDeviceToken) {
        return new TokenResponse(
                tokens.accessToken(),
                "Bearer",
                tokens.expiresIn(),
                includeRefresh ? tokens.refreshToken() : null,
                trustedDeviceToken);
    }
}
