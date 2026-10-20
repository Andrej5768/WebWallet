package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.EmailRequest;
import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.dto.ResetPasswordRequest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.dto.TwoFactorLoginRequest;
import com.andrewcorp.webwallet.auth.dto.VerifyEmailRequest;
import com.andrewcorp.webwallet.auth.twofactor.TrustedDeviceCookie;
import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
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

    private final AuthService authService;
    private final TokenService tokenService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final TwoFactorService twoFactor;
    private final TrustedDeviceCookie trustedDeviceCookie;

    public AuthController(
            AuthService authService,
            TokenService tokenService,
            RefreshTokenCookie refreshTokenCookie,
            TwoFactorService twoFactor,
            TrustedDeviceCookie trustedDeviceCookie) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.twoFactor = twoFactor;
        this.trustedDeviceCookie = trustedDeviceCookie;
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
                    + " to /api/v1/auth. With two-factor authentication on, unless the browser is trusted, returns"
                    + " twoFactorChallenge instead, to exchange with a code at /api/v1/auth/login/two-factor.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @Parameter(description = "Set when the browser was trusted at an earlier two-factor sign-in")
                    @CookieValue(name = TrustedDeviceCookie.NAME, required = false)
                    String trustedDevice,
            HttpServletRequest httpRequest) {
        AuthService.LoginResult result = authService.login(request, trustedDevice, httpRequest.getRemoteAddr());
        if (result.tokens() == null) {
            return ResponseEntity.ok(LoginResponse.challenge(result.twoFactorChallenge()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.of(result.tokens()))
                .body(LoginResponse.session(
                        result.tokens().accessToken(), result.tokens().expiresIn()));
    }

    @Operation(
            summary = "Finish signing in with a two-factor code",
            description = "Exchanges the challenge of the login response and a code of the authenticator app or a"
                    + " recovery code for a session. A wrong code keeps the challenge until it expires; an expired one"
                    + " fails with TWO_FACTOR_EXPIRED. With trustDevice the browser gets a cookie that skips the code"
                    + " for a while.")
    @PostMapping("/login/two-factor")
    public ResponseEntity<TokenResponse> loginTwoFactor(@Valid @RequestBody TwoFactorLoginRequest request) {
        TwoFactorService.Login login =
                twoFactor.completeLogin(request.challenge(), request.code(), request.trustDevice());
        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshTokenCookie.of(login.tokens()));
        if (login.trustedDeviceToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, trustedDeviceCookie.of(login.trustedDeviceToken()));
        }
        return response.body(TokenResponse.bearer(
                login.tokens().accessToken(), login.tokens().expiresIn()));
    }

    @Operation(
            summary = "Exchange the refresh token cookie for a new access token",
            description = "The refresh token is rotated: the response sets a new cookie and the presented token stops"
                    + " working. Presenting an already rotated token ends the whole session.")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Parameter(description = "Refresh token set by login or a previous refresh")
                    @CookieValue(name = REFRESH_COOKIE, required = false)
                    String refreshToken) {
        if (refreshToken == null) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return refreshTokenCookie.respond(tokenService.refresh(refreshToken));
    }

    @Operation(
            summary = "End the session of the refresh token cookie and delete the cookie",
            description = "Succeeds without the cookie or with an unknown token as well")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Parameter(description = "Refresh token set by login or a previous refresh")
                    @CookieValue(name = REFRESH_COOKIE, required = false)
                    String refreshToken,
            HttpServletResponse response) {
        if (refreshToken != null) {
            tokenService.revokeSession(refreshToken);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.cleared());
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
}
