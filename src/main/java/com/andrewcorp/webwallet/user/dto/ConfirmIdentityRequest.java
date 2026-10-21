package com.andrewcorp.webwallet.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Confirms that the owner of the account asks for a change of its security, not someone with a stolen session.
 *
 * @param password the current password; ignored for an account without one (signed up with Google)
 * @param code     for an account without a password and with two-factor authentication: six digits of the app or a
 *                 recovery code
 */
public record ConfirmIdentityRequest(
        @Schema(nullable = true) @Size(max = 128) String password,
        @Schema(nullable = true) @Size(max = 32) String code) {}
