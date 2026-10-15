package com.andrewcorp.webwallet.auth.dto;

import com.andrewcorp.webwallet.common.validation.Password;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param lastName optional; blank means none */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Password String password,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName) {}
