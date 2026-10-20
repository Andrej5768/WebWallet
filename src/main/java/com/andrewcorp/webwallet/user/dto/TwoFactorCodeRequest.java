package com.andrewcorp.webwallet.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param code six digits of the authenticator app */
public record TwoFactorCodeRequest(@NotBlank @Size(max = 32) String code) {}
