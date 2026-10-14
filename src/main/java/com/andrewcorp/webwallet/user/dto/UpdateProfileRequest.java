package com.andrewcorp.webwallet.user.dto;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Pattern(regexp = "en|uk") String locale,
        @NotBlank @CurrencyCode String baseCurrency) {
}
