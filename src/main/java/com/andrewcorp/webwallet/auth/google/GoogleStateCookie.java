package com.andrewcorp.webwallet.auth.google;

import com.andrewcorp.webwallet.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Binds a Google sign-in to the browser that started it. SameSite Lax, so that the browser sends it with the
 * redirect back from Google, which is a top-level navigation from another site.
 */
@Component
public class GoogleStateCookie {

    public static final String NAME = "oauth_state";
    private static final String PATH = "/api/v1/auth/google";

    private final boolean secure;

    public GoogleStateCookie(AppProperties properties) {
        this.secure = properties.auth().refreshCookieSecure();
    }

    public String of(String state) {
        return cookie(state, Duration.ofMinutes(10));
    }

    public String cleared() {
        return cookie("", Duration.ZERO);
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
