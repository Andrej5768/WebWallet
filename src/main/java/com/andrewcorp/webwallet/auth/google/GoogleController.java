package com.andrewcorp.webwallet.auth.google;

import com.andrewcorp.webwallet.auth.RefreshTokenCookie;
import com.andrewcorp.webwallet.auth.dto.AuthOptionsResponse;
import com.andrewcorp.webwallet.auth.dto.GoogleTicketRequest;
import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.auth.twofactor.TrustedDeviceCookie;
import com.andrewcorp.webwallet.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
public class GoogleController {

    private final GoogleSignInService googleSignIn;
    private final GoogleStateCookie stateCookie;
    private final RefreshTokenCookie refreshTokenCookie;
    private final String frontendUrl;

    public GoogleController(
            GoogleSignInService googleSignIn,
            GoogleStateCookie stateCookie,
            RefreshTokenCookie refreshTokenCookie,
            AppProperties properties) {
        this.googleSignIn = googleSignIn;
        this.stateCookie = stateCookie;
        this.refreshTokenCookie = refreshTokenCookie;
        this.frontendUrl = properties.frontendUrl("");
    }

    @Operation(summary = "Which ways of signing in this server offers besides the password")
    @GetMapping("/options")
    public AuthOptionsResponse options() {
        return new AuthOptionsResponse(googleSignIn.enabled());
    }

    @Operation(
            summary = "Start signing in with Google",
            description = "A link for the browser: redirects to Google and sets a cookie that binds the sign-in to"
                    + " this browser. Not found when sign-in with Google is not configured.")
    @GetMapping("/google/start")
    public ResponseEntity<Void> start() {
        GoogleSignInService.Started started = googleSignIn.start(OAuthState.Purpose.LOGIN, null);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(started.url()))
                .header(HttpHeaders.SET_COOKIE, stateCookie.of(started.state()))
                .build();
    }

    @Operation(
            summary = "Google redirects here after the user chose an account",
            description = "Redirects to the web client: /auth/google#ticket=... to exchange at /google/complete, or"
                    + " #error=...; for linking, to the security settings with #google=linked or #error=...")
    @GetMapping("/google/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @Parameter(description = "Set by /google/start or /api/v1/me/google/link")
                    @CookieValue(name = GoogleStateCookie.NAME, required = false)
                    String stateInCookie,
            Locale locale) {
        String page = googleSignIn.callback(stateInCookie, state, code, error, locale);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendUrl + page))
                .header(HttpHeaders.SET_COOKIE, stateCookie.cleared())
                .build();
    }

    @Operation(
            summary = "Finish signing in with Google",
            description = "Exchanges the ticket of the callback for a session, like /login: the access token, or"
                    + " twoFactorChallenge when two-factor authentication is on. The ticket works once and briefly.")
    @PostMapping("/google/complete")
    public ResponseEntity<LoginResponse> complete(
            @Valid @RequestBody GoogleTicketRequest request,
            @Parameter(description = "Set when the browser was trusted at an earlier two-factor sign-in")
                    @CookieValue(name = TrustedDeviceCookie.NAME, required = false)
                    String trustedDevice) {
        return refreshTokenCookie.respond(googleSignIn.complete(request.ticket(), trustedDevice));
    }
}
