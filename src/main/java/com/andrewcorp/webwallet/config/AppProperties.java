package com.andrewcorp.webwallet.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String frontendUrl,
        @NotNull Mail mail,
        @NotNull Cors cors,
        @NotNull Jwt jwt,
        @NotNull Auth auth) {

    public record Mail(@NotBlank String from) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {}

    /**
     * @param maxFailedLogins failed logins after which the client IP is blocked for {@code loginBlockDuration}
     *     and the account for {@code accountBlockDuration}
     * @param maxEmailRequestsPerAddress requests that send an email (registration, verification, password reset)
     *     per address within {@code emailRequestWindow}
     */
    public record Auth(
            @NotNull Duration emailVerificationTtl,
            @NotNull Duration passwordResetTtl,
            @Positive int maxFailedLogins,
            @NotNull Duration loginBlockDuration,
            @NotNull Duration accountBlockDuration,
            @Positive int maxEmailRequestsPerAddress,
            @Positive int maxEmailRequestsPerIp,
            @NotNull Duration emailRequestWindow) {}
}
