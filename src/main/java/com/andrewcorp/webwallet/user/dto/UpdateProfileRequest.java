package com.andrewcorp.webwallet.user.dto;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param lastName optional; omitted or blank removes it */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @Schema(nullable = true) @Size(max = 100) String lastName,
        @NotBlank @Pattern(regexp = "en|uk") String locale,
        @NotBlank @CurrencyCode String baseCurrency) {}
