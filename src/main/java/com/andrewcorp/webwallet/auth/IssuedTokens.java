package com.andrewcorp.webwallet.auth;

/**
 * Tokens of a new or refreshed session. The access token is returned in the response body, the refresh token only
 * in a cookie (see {@link RefreshTokenCookie}).
 *
 * @param expiresIn lifetime of the access token in seconds
 */
public record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {}
