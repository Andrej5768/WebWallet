package com.andrewcorp.webwallet.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshTokenRequestTest {

    @Test
    void theTokenDoesNotAppearWhenTheRequestIsPrinted() {
        RefreshTokenRequest request = new RefreshTokenRequest("secret-token");

        assertThat(request.toString()).doesNotContain("secret-token").isEqualTo("RefreshTokenRequest[***]");
        assertThat(request.refreshToken()).isEqualTo("secret-token");
    }

    @Test
    void thePasswordDoesNotAppearWhenTheLoginIsPrinted() {
        LoginRequest request = new LoginRequest("a@b.c", "secret-password", "device-token");

        assertThat(request.toString())
                .doesNotContain("secret-password", "device-token")
                .contains("a@b.c");
    }
}
