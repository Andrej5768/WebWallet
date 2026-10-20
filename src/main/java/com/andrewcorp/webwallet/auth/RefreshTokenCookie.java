package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.config.AppProperties;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Keeps the refresh token in an HttpOnly cookie, so that scripts of the web client cannot read it. The cookie is
 * sent only to the auth endpoints and never with cross-site requests, which also protects them from CSRF.
 */
@Component
public class RefreshTokenCookie {

    public static final String PATH = "/api/v1/auth";

    private final String name;
    private final boolean secure;
    private final Duration maxAge;

    public RefreshTokenCookie(AppProperties properties) {
        this.name = properties.auth().refreshCookieName();
        this.secure = properties.auth().refreshCookieSecure();
        this.maxAge = properties.jwt().refreshTokenTtl();
    }

    /** Returns the access token in the body and sets the refresh token cookie. */
    public ResponseEntity<TokenResponse> respond(IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), maxAge))
                .body(TokenResponse.bearer(tokens.accessToken(), tokens.expiresIn()));
    }

    /** Returns a session like {@link #respond(IssuedTokens)}, or the challenge of the second step without a cookie. */
    public ResponseEntity<LoginResponse> respond(AuthService.LoginResult result) {
        if (result.tokens() == null) {
            return ResponseEntity.ok(LoginResponse.challenge(result.twoFactorChallenge()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, of(result.tokens()))
                .body(LoginResponse.session(
                        result.tokens().accessToken(), result.tokens().expiresIn()));
    }

    /** A {@code Set-Cookie} value with the refresh token of the session. */
    public String of(IssuedTokens tokens) {
        return cookie(tokens.refreshToken(), maxAge);
    }

    /** A {@code Set-Cookie} value that makes the browser delete the refresh token. */
    public String cleared() {
        return cookie("", Duration.ZERO);
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
