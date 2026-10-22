package com.andrewcorp.webwallet.auth;

/**
 * Tokens of a new or refreshed session. The access token is returned in the response body, the refresh token in a
 * cookie for the web client and in the body for the native apps (see {@link SessionResponse}).
 *
 * @param expiresIn lifetime of the access token in seconds
 */
public record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {}
