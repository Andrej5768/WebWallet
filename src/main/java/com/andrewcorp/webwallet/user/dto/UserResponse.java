package com.andrewcorp.webwallet.user.dto;

import com.andrewcorp.webwallet.user.Role;
import com.andrewcorp.webwallet.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * @param lastName null when not given
 * @param recoveryCodesLeft unused recovery codes, 0 while two-factor authentication is off
 * @param hasPassword false for an account created with Google until a password is set
 * @param googleEmail email of the linked Google account, null when none is linked
 * @param demo a sandbox opened without registration; {@code demoExpiresAt} says when it is deleted
 */
public record UserResponse(
        Long id,
        String email,
        String firstName,
        @Schema(nullable = true) String lastName,
        Role role,
        boolean emailVerified,
        String locale,
        String baseCurrency,
        Instant createdAt,
        boolean twoFactorEnabled,
        long recoveryCodesLeft,
        boolean hasPassword,
        @Schema(nullable = true) String googleEmail,
        boolean demo,
        @Schema(nullable = true) Instant demoExpiresAt) {

    public static UserResponse from(User user, long recoveryCodesLeft) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified(),
                user.getLocale(),
                user.getBaseCurrency(),
                user.getCreatedAt(),
                user.isTwoFactorEnabled(),
                recoveryCodesLeft,
                user.hasPassword(),
                user.getGoogleEmail(),
                user.isDemo(),
                user.getDemoExpiresAt());
    }
}
