package com.andrewcorp.webwallet.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password the current password; ignored for an account without one (signed up with Google)
 * @param code six digits of the authenticator app or a recovery code
 */
public record DisableTwoFactorRequest(
        @Schema(nullable = true) @Size(max = 128) String password,
        @NotBlank @Size(max = 32) String code) {}
