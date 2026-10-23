package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Keeps the refresh token of the web client in an HttpOnly cookie, so that scripts cannot read it. The cookie is
 * sent only to the auth endpoints and never with cross-site requests, which also protects them from CSRF. The
 * native apps get the token in the body instead, see {@link SessionResponse}.
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
