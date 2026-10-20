package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param token restoreToken of an ACCOUNT_DELETION_SCHEDULED problem, or the token of the emailed link */
public record RestoreAccountRequest(
        @NotBlank @Size(max = 128) String token) {}
