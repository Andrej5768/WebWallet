package com.andrewcorp.webwallet.user.dto;

import com.andrewcorp.webwallet.common.validation.Password;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Password String newPassword) {}
