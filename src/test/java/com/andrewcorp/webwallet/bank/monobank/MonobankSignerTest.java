package com.andrewcorp.webwallet.bank.monobank;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

class MonobankSignerTest {

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    /** A fresh secp256k1 key pair, the curve the bank registers keys with. */
    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BC);
        generator.initialize(new ECGenParameterSpec("secp256k1"));
        return generator.generateKeyPair();
    }

    static String pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    static boolean verifies(KeyPair pair, String text, String signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA", BC);
        verifier.initVerify(pair.getPublic());
        verifier.update(text.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signature));
    }

    @Test
    void signsWithTheKeyAsPemOnSeveralLinesOrOne() throws Exception {
        KeyPair pair = keyPair();
        String pem = pem(pair);

        for (String variant : new String[] {pem, pem.replace("\n", ""), pem.replace("\n", "\\n")}) {
            MonobankSigner signer = new MonobankSigner("key-1", variant);

            assertThat(signer.enabled()).isTrue();
            assertThat(signer.keyId()).isEqualTo("key-1");
            assertThat(verifies(
                            pair, "1790000000/personal/auth/request", signer.sign("1790000000/personal/auth/request")))
                    .isTrue();
        }
    }

    @Test
    void isOffWithoutTheKeyIdTheKeyOrWithABrokenKey() throws Exception {
        assertThat(new MonobankSigner("", pem(keyPair())).enabled()).isFalse();
        assertThat(new MonobankSigner("key-1", "").enabled()).isFalse();
        assertThat(new MonobankSigner("key-1", "-----BEGIN PRIVATE KEY-----not a key-----END PRIVATE KEY-----")
                        .enabled())
                .isFalse();
    }
}
