package com.andrewcorp.webwallet.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The SHA-1 key of the test vectors in RFC 6238, appendix B. */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void generatesTheCodesOfRfc6238() {
        byte[] key = Totp.unbase32(RFC_SECRET);
        // The RFC lists 8 digits; authenticator apps show the last 6
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
    }

    @Test
    void acceptsTheNeighbouringStepsAndRejectsUsedOnes() {
        Instant now = Instant.ofEpochSecond(1234567890);
        long step = Totp.step(now);
        byte[] key = Totp.unbase32(RFC_SECRET);

        assertThat(Totp.verify(RFC_SECRET, "005924", now, null)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, "005 924", now, null)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step + 2), now, null)).isEqualTo(-1);
        // The step was used already
        assertThat(Totp.verify(RFC_SECRET, "005924", now, step)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, null)).isEqualTo(-1);
    }

    @Test
    void roundTripsBase32AndBuildsTheLinkForTheApp() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.base32(Totp.unbase32(secret))).isEqualTo(secret);

        assertThat(Totp.uri("WebWallet", "olena@example.com", "ABC"))
                .isEqualTo("otpauth://totp/WebWallet:olena%40example.com?secret=ABC&issuer=WebWallet"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
