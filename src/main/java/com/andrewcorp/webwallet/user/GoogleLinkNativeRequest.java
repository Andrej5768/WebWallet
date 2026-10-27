package com.andrewcorp.webwallet.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Linking the Google account from a native app (B3): the ID token Google handed the app, after the identity check
 * of the web link (the current password, or for an account without one a two-factor code when it is on).
 *
 * @param idToken the Google ID token of the chosen account
 * @param password the current password
 * @param code a two-factor or recovery code, for an account without a password
 */
public record GoogleLinkNativeRequest(
        @NotBlank @Size(max = 4096) String idToken,
        @Size(max = 200) String password,
        @Size(max = 32) String code) {}
