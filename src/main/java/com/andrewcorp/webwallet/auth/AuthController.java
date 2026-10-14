package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.EmailRequest;
import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.RefreshTokenRequest;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.dto.ResetPasswordRequest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.dto.VerifyEmailRequest;
import com.andrewcorp.webwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @Operation(summary = "Register a new user and send an email verification link")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request, Locale locale) {
        return UserResponse.from(authService.register(request, locale));
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
    public void resendVerification(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request.email());
    }

    @Operation(summary = "Sign in with email and password")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Exchange a refresh token for a new token pair")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    @Operation(summary = "End the session of the given refresh token")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokenService.revokeSession(request.refreshToken());
    }

    @Operation(summary = "Send a password reset link")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request.email());
    }

    @Operation(summary = "Set a new password with the token from the password reset email")
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
    }
}
