package com.andrewcorp.webwallet.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static String key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void roundTripWithRandomIv() {
        SecretCipher cipher = new SecretCipher(key(32));

        String first = cipher.encrypt("monobank-token");
        String second = cipher.encrypt("monobank-token");

        assertThat(first).isNotEqualTo(second).doesNotContain("monobank-token");
        assertThat(cipher.decrypt(first)).isEqualTo("monobank-token");
        assertThat(cipher.decrypt(second)).isEqualTo("monobank-token");
    }

    @Test
    void tamperedDataIsRejected() {
        SecretCipher cipher = new SecretCipher(key(32));
        byte[] encrypted = Base64.getDecoder().decode(cipher.encrypt("secret"));
        encrypted[encrypted.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(encrypted)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustHave32Bytes() {
        assertThatThrownBy(() -> new SecretCipher(key(16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
