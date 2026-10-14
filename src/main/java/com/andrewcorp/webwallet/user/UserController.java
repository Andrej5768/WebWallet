package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.auth.AuthService;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.user.dto.ChangePasswordRequest;
import com.andrewcorp.webwallet.user.dto.UpdateProfileRequest;
import com.andrewcorp.webwallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Current user")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/me")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @Operation(summary = "Get the profile of the current user")
    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.get(userId(jwt)));
    }

    @Operation(summary = "Update the profile of the current user")
    @PutMapping
    public UserResponse updateProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(userService.updateProfile(userId(jwt), request));
    }

    @Operation(summary = "Change the password; all other sessions are ended and a new token pair is returned")
    @PostMapping("/password")
    public TokenResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(userId(jwt), request.currentPassword(), request.newPassword());
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
