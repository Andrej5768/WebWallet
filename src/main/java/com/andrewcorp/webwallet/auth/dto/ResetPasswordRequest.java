package com.andrewcorp.webwallet.auth.dto;

import com.andrewcorp.webwallet.common.validation.Password;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 128) String token,
        @NotBlank @Password String newPassword) {}
