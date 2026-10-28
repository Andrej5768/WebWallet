package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Signing in from a native app with the ID token that Google handed the app (B3).
 *
 * @param idToken the Google ID token of the chosen account
 * @param trustedDeviceToken returned to native apps by a two-factor sign-in with trustDevice
 */
public record GoogleIdTokenRequest(
        @NotBlank @Size(max = 4096) String idToken,
        @Size(max = 128) String trustedDeviceToken) {}
