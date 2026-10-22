package com.andrewcorp.webwallet.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    @Test
    void aLatestAndroidVersionBelowTheMinimumIsRejectedAtStartup() {
        assertThatThrownBy(() -> new AppProperties.Android(5, 3, "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latest-version-code")
                .hasMessageContaining("min-supported-version-code");
    }

    @Test
    void theLatestAndroidVersionMayEqualTheMinimum() {
        assertThatCode(() -> new AppProperties.Android(5, 5, "https://example.com"))
                .doesNotThrowAnyException();
    }
}
