package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.EmailRequest;
import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.dto.ResetPasswordRequest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.dto.VerifyEmailRequest;
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

    public AuthController(AuthService authService, TokenService tokenService, RefreshTokenCookie refreshTokenCookie) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.refreshTokenCookie = refreshTokenCookie;
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
                    + " to /api/v1/auth")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return refreshTokenCookie.respond(authService.login(request, httpRequest.getRemoteAddr()));
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
