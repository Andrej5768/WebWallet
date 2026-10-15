package com.andrewcorp.webwallet.user.dto;

import com.andrewcorp.webwallet.user.Role;
import com.andrewcorp.webwallet.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** @param lastName null when not given */
public record UserResponse(
        Long id,
        String email,
        String firstName,
        @Schema(nullable = true) String lastName,
        Role role,
        boolean emailVerified,
        String locale,
        String baseCurrency,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified(),
                user.getLocale(),
                user.getBaseCurrency(),
                user.getCreatedAt());
    }
}
