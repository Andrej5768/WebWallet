package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sizes are limited, as failed logins are remembered by email. */
public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 128) String password) {}
