package com.andrewcorp.webwallet.auth.twofactor;

import com.andrewcorp.webwallet.auth.RefreshTokenCookie;
import com.andrewcorp.webwallet.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Marks a browser where the two-factor code is not asked again. Like the refresh token, it is HttpOnly and sent only
 * to the auth endpoints.
 */
@Component
public class TrustedDeviceCookie {

    public static final String NAME = "trusted_device";

    private final boolean secure;
    private final Duration maxAge;

    public TrustedDeviceCookie(AppProperties properties) {
        this.secure = properties.auth().refreshCookieSecure();
        this.maxAge = properties.auth().trustedDeviceTtl();
    }

    /** A {@code Set-Cookie} value with the token of a newly trusted device. */
    public String of(String rawToken) {
        return ResponseCookie.from(NAME, rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(RefreshTokenCookie.PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
