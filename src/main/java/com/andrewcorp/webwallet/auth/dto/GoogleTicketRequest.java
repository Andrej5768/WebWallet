package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param ticket from the fragment of the page the Google callback redirected to
 * @param trustedDeviceToken returned to native apps by a two-factor sign-in with trustDevice; browsers keep it in a
 *     cookie instead
 */
public record GoogleTicketRequest(
        @NotBlank @Size(max = 128) String ticket,
        @Size(max = 128) String trustedDeviceToken) {}
