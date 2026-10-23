package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sizes are limited, as failed logins are remembered by email.
 *
 * @param trustedDeviceToken returned to native apps by a two-factor sign-in with trustDevice; browsers keep it in a
 *     cookie instead
 */
public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password,
        @Size(max = 128) String trustedDeviceToken) {

    /** The password must never end up in a log. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
