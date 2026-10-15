package com.andrewcorp.webwallet.auth.dto;

/**
 * The refresh token is not part of the body; it is set in an HttpOnly cookie.
 *
 * @param expiresIn lifetime of the access token in seconds
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
