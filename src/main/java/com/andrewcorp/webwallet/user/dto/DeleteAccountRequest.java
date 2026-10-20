package com.andrewcorp.webwallet.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param password the current password; for an account without one, confirmEmail instead
 * @param code six digits of the authenticator app or a recovery code, when two-factor authentication is on
 * @param confirmEmail the email of the account, typed again; only for an account without a password
 */
public record DeleteAccountRequest(
        @Schema(nullable = true) @Size(max = 128) String password,
        @Schema(nullable = true) @Size(max = 32) String code,
        @Schema(nullable = true) @Size(max = 320) String confirmEmail) {}
