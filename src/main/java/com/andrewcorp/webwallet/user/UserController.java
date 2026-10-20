package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.auth.AuthService;
import com.andrewcorp.webwallet.auth.RefreshTokenCookie;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.user.dto.ChangePasswordRequest;
import com.andrewcorp.webwallet.user.dto.ResetRequest;
import com.andrewcorp.webwallet.user.dto.UpdateProfileRequest;
import com.andrewcorp.webwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Current user")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/me")
public class UserController {

    private final UserService userService;
    private final UserDataService userDataService;
    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final TwoFactorService twoFactor;

    public UserController(
            UserService userService,
            UserDataService userDataService,
            AuthService authService,
            RefreshTokenCookie refreshTokenCookie,
            TwoFactorService twoFactor) {
        this.userService = userService;
        this.userDataService = userDataService;
        this.authService = authService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.twoFactor = twoFactor;
    }

    private UserResponse response(User user) {
        return UserResponse.from(user, twoFactor.recoveryCodesLeft(user.getId()));
    }

    @Operation(summary = "Get the profile of the current user")
    @GetMapping
    public UserResponse me(@CurrentUserId Long userId) {
        return response(userService.get(userId));
    }

    @Operation(summary = "Update the profile of the current user")
    @PutMapping
    public UserResponse updateProfile(@CurrentUserId Long userId, @Valid @RequestBody UpdateProfileRequest request) {
        return response(userService.updateProfile(userId, request));
    }

    @Operation(
            summary = "Change the password",
            description = "All sessions are ended, including the current one; a new session is started: the response"
                    + " contains a new access token and sets a new refresh token cookie")
    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(
            @CurrentUserId Long userId, @Valid @RequestBody ChangePasswordRequest request) {
        return refreshTokenCookie.respond(
                authService.changePassword(userId, request.currentPassword(), request.newPassword()));
    }

    @Operation(
            summary = "Start over: delete all finance data",
            description = "Deletes all accounts, transactions, payees, tags, the budget, bank connections and import"
                    + " previews; with keepCategories false also the categories and categorization rules, and the"
                    + " default categories are created again. confirm must be exactly DELETE. The profile, the"
                    + " password and the sessions stay.")
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@CurrentUserId Long userId, @RequestBody ResetRequest request) {
        userDataService.reset(userId, request);
    }
}
