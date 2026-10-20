package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param challenge from the login response
 * @param code six digits of the authenticator app or a recovery code
 * @param trustDevice whether this browser may skip the code from now on
 */
public record TwoFactorLoginRequest(
        @NotBlank @Size(max = 128) String challenge,
        @NotBlank @Size(max = 32) String code,
        boolean trustDevice) {}
