package com.andrewcorp.webwallet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    private static AppProperties properties(String secret) {
        return new AppProperties(
                "http://localhost",
                new AppProperties.Mail("a@b.c"),
                new AppProperties.Cors(List.of()),
                new AppProperties.Jwt(secret, "test", Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Auth(
                        Duration.ofHours(24),
                        Duration.ofHours(1),
                        10,
                        Duration.ofHours(24),
                        Duration.ofHours(1),
                        5,
                        20,
                        Duration.ofHours(1)));
    }

    @Test
    void shortJwtSecretIsRejectedAtStartup() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecurityConfig().jwtSecretKey(properties(shortSecret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void validJwtSecretIsAccepted() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(new SecurityConfig().jwtSecretKey(properties(secret)).getAlgorithm())
                .isEqualTo("HmacSHA256");
    }
}
