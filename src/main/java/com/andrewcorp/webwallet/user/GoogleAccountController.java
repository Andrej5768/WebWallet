package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.auth.google.GoogleSignInService;
import com.andrewcorp.webwallet.auth.google.GoogleStateCookie;
import com.andrewcorp.webwallet.auth.google.OAuthState;
import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.user.dto.ConfirmIdentityRequest;
import com.andrewcorp.webwallet.user.dto.RedirectResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Current user")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/me/google")
public class GoogleAccountController {

    private final GoogleSignInService googleSignIn;
    private final GoogleStateCookie stateCookie;
    private final TwoFactorService twoFactor;

    public GoogleAccountController(
            GoogleSignInService googleSignIn, GoogleStateCookie stateCookie, TwoFactorService twoFactor) {
        this.googleSignIn = googleSignIn;
        this.stateCookie = stateCookie;
        this.twoFactor = twoFactor;
    }

    @Operation(
            summary = "Start linking a Google account",
            description = "Returns the Google page to send the browser to and sets the cookie that binds the link to"
                    + " this browser; Google then redirects to the security settings. Needs the current password, or"
                    + " for an account without one a two-factor code when it is on.")
    @PostMapping("/link")
    public ResponseEntity<RedirectResponse> link(
            @CurrentUserId Long userId, @Valid @RequestBody ConfirmIdentityRequest request) {
        twoFactor.confirmIdentity(userId, request.password(), request.code());
        GoogleSignInService.Started started = googleSignIn.start(OAuthState.Purpose.LINK, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, stateCookie.of(started.state()))
                .body(new RedirectResponse(started.url()));
    }

    @Operation(
            summary = "Link the Google account of a native app",
            description = "The ID token the app got from Google, after the same identity check as the browser link."
                    + " GOOGLE_ACCOUNT_TAKEN when the Google account belongs to another user.")
    @PostMapping("/link/native")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void linkNative(@CurrentUserId Long userId, @Valid @RequestBody GoogleLinkNativeRequest request) {
        twoFactor.confirmIdentity(userId, request.password(), request.code());
        googleSignIn.linkNative(userId, request.idToken());
    }

    @Operation(
            summary = "Unlink the Google account",
            description = "CONFLICT for an account without a password, which could not sign in otherwise")
    @PostMapping("/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@CurrentUserId Long userId) {
        googleSignIn.unlink(userId);
    }
}
