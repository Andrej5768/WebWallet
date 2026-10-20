package com.andrewcorp.webwallet.tax.declaration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the declarations need about the taxpayer, as the tax service formats require it.
 *
 * @param tin          РНОКПП (10 digits), or the series and number of a passport of those who refused it
 * @param name         full name as in the tax register
 * @param regionCode   code of the region of the tax office the taxpayer is registered with (C_REG), 1..99
 * @param districtCode code of its district (C_RAJ), 0..99; the office code is region × 100 + district
 * @param taxOffice    name of the tax office, e.g. «ГУ ДПС у м. Києві»
 * @param region       region of the address, may be empty for Kyiv
 * @param kvedCode     main activity of the sole proprietor, e.g. 62.01
 * @param kvedName     its name
 */
public record TaxProfileData(
        @NotBlank @Pattern(regexp = "[0-9]{10}|[0-9]{9}|[АБВГДЕЄЖЗИІКЛМНОПРСТУФХЦЧШЩЮЯ]{2}[0-9]{6}") String tin,

        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(1) @Max(99) Integer regionCode,
        @NotNull @Min(0) @Max(99) Integer districtCode,
        @NotBlank @Size(max = 200) String taxOffice,
        @Schema(nullable = true) @Size(max = 100) String region,
        @Schema(nullable = true) @Size(max = 100) String district,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 200) String street,
        @Schema(nullable = true) @Size(max = 20) String building,
        @Schema(nullable = true) @Size(max = 20) String corpus,
        @Schema(nullable = true) @Size(max = 20) String apartment,

        @Schema(nullable = true) @Pattern(regexp = "[0-9]{5}") String zip,

        @Schema(nullable = true) @Pattern(regexp = "[0-9 ()\\-+.,;]{4,}") @Size(max = 30) String phone,

        @Schema(nullable = true) @Email @Size(max = 100) String email,

        @Schema(nullable = true) @Pattern(regexp = "[0-9]{2}\\.([0-9]{1,2}|[0-9]{2}\\.[0-9])")
        String kvedCode,

        @Schema(nullable = true) @Size(max = 250) String kvedName) {

    /** The address in one line, for the declaration of the sole proprietor. */
    public String addressLine() {
        StringBuilder line = new StringBuilder();
        append(line, zip);
        append(line, region);
        append(line, district);
        append(line, city);
        append(line, street);
        append(line, building != null ? "буд. " + building : null);
        append(line, corpus != null ? "корп. " + corpus : null);
        append(line, apartment != null ? "кв. " + apartment : null);
        return line.toString();
    }

    /** The code of the tax office: region × 100 + district. */
    public int taxOfficeCode() {
        return regionCode * 100 + districtCode;
    }

    private static void append(StringBuilder line, String part) {
        if (part != null && !part.isBlank()) {
            if (!line.isEmpty()) {
                line.append(", ");
            }
            line.append(part.trim());
        }
    }
}
