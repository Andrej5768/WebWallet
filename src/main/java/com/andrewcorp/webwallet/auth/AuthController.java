package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.EmailRequest;
import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.auth.dto.RefreshTokenRequest;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.dto.ResetPasswordRequest;
import com.andrewcorp.webwallet.auth.dto.RestoreAccountRequest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.dto.TwoFactorLoginRequest;
import com.andrewcorp.webwallet.auth.dto.VerifyEmailRequest;
import com.andrewcorp.webwallet.auth.twofactor.TrustedDeviceCookie;
import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.user.AccountDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Name of the refresh token cookie from {@code AppProperties.Auth#refreshCookieName}. */
    private static final String REFRESH_COOKIE = "${app.auth.refresh-cookie-name}";

    private static final String NATIVE_APPS = " Native apps (header X-WebWallet-Client) get the refresh token in"
            + " refreshToken instead of the cookie and send it in the body.";

    private final AuthService authService;
    private final TokenService tokenService;
    private final SessionResponse sessionResponse;
    private final RefreshTokenCookie refreshTokenCookie;
    private final TwoFactorService twoFactor;
    private final AccountDeletionService accountDeletion;

    public AuthController(
            AuthService authService,
            TokenService tokenService,
            SessionResponse sessionResponse,
            RefreshTokenCookie refreshTokenCookie,
            TwoFactorService twoFactor,
            AccountDeletionService accountDeletion) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.sessionResponse = sessionResponse;
        this.refreshTokenCookie = refreshTokenCookie;
        this.twoFactor = twoFactor;
        this.accountDeletion = accountDeletion;
    }

    @Operation(
            summary = "Register a new user and send an email verification link",
            description = "The response is the same when the email is taken; the owner gets an email instead")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest request, Locale locale, HttpServletRequest httpRequest) {
        authService.register(request, locale, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Confirm the email address with the token from the verification email")
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
    }

    @Operation(summary = "Send the email verification link again")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest httpRequest) {
        authService.resendVerification(request.email(), httpRequest.getRemoteAddr());
    }

    @Operation(
            summary = "Sign in with email and password",
            description = "Returns the access token; the refresh token is set in an HttpOnly cookie that is sent only"
                    + " to /api/v1/auth. With two-factor authentication on, unless the device is trusted, returns"
                    + " twoFactorChallenge instead, to exchange with a code at /api/v1/auth/login/two-factor."
                    + NATIVE_APPS + " Native apps send trustedDeviceToken in the body.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @Parameter(description = "Set when the browser was trusted at an earlier two-factor sign-in")
                    @CookieValue(name = TrustedDeviceCookie.NAME, required = false)
                    String trustedDevice,
            HttpServletRequest httpRequest,
            ClientInfo client) {
        String trusted = SessionResponse.presented(request.trustedDeviceToken(), trustedDevice);
        return sessionResponse.login(
                authService.login(request, trusted, httpRequest.getRemoteAddr(), client.kind()), client);
    }

    @Operation(
            summary = "Finish signing in with a two-factor code",
            description = "Exchanges the challenge of the login response and a code of the authenticator app or a"
                    + " recovery code for a session. A wrong code keeps the challenge until it expires; an expired one"
                    + " fails with TWO_FACTOR_EXPIRED. With trustDevice the browser gets a cookie that skips the code"
                    + " for a while." + NATIVE_APPS
                    + " Native apps get the trusted device as trustedDeviceToken in the body.")
    @PostMapping("/login/two-factor")
    public ResponseEntity<TokenResponse> loginTwoFactor(
            @Valid @RequestBody TwoFactorLoginRequest request, ClientInfo client) {
        TwoFactorService.Login login =
                twoFactor.completeLogin(request.challenge(), request.code(), request.trustDevice(), client.kind());
        return sessionResponse.tokens(login.tokens(), login.trustedDeviceToken(), client);
    }

    @Operation(
            summary = "Exchange the refresh token for a new access token",
            description = "The refresh token is rotated: the response sets a new cookie and the presented token stops"
                    + " working. Presenting an already rotated token ends the whole session." + NATIVE_APPS)
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Valid @RequestBody(required = false) RefreshTokenRequest body,
            @Parameter(description = "Refresh token set by login or a previous refresh")
                    @CookieValue(name = REFRESH_COOKIE, required = false)
                    String cookie,
            ClientInfo client) {
        String presented = presentedToken(body, cookie);
        if (presented == null) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return sessionResponse.tokens(tokenService.refresh(presented, client.kind()), client);
    }

    @Operation(
            summary = "End the session of the refresh token and delete the cookie",
            description = "Succeeds without a token or with an unknown one as well." + NATIVE_APPS)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Valid @RequestBody(required = false) RefreshTokenRequest body,
            @Parameter(description = "Refresh token set by login or a previous refresh")
                    @CookieValue(name = REFRESH_COOKIE, required = false)
                    String cookie,
            ClientInfo client,
            HttpServletResponse response) {
        String presented = presentedToken(body, cookie);
        if (presented != null) {
            tokenService.revokeSession(presented);
        }
        if (!client.nativeApp()) {
            response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.cleared());
        }
    }

    @Operation(
            summary = "Cancel the deletion of the account",
            description = "With restoreToken of an ACCOUNT_DELETION_SCHEDULED problem also signs in, like /login;"
                    + " with the token of the emailed link only cancels, and the response has no tokens")
    @PostMapping("/restore")
    public ResponseEntity<LoginResponse> restore(@Valid @RequestBody RestoreAccountRequest request, ClientInfo client) {
        IssuedTokens tokens = accountDeletion.restore(request.token(), client.kind());
        return tokens == null
                ? ResponseEntity.ok(LoginResponse.none())
                : sessionResponse.login(new AuthService.LoginResult(tokens, null), client);
    }

    @Operation(summary = "Send a password reset link")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest httpRequest) {
        authService.forgotPassword(request.email(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Set a new password with the token from the password reset email")
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
    }

    /** The refresh token of the request: the body of a native app first, then the cookie of the browser. */
    private static String presentedToken(RefreshTokenRequest body, String cookie) {
        return SessionResponse.presented(body == null ? null : body.refreshToken(), cookie);
    }
}
