package com.andrewcorp.webwallet.bank.monobank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs the requests of WebWallet as a Monobank service provider: ECDSA with SHA-256 over secp256k1, the curve the
 * bank registered the public key with. The JDK has dropped secp256k1, so the key is read and used through
 * BouncyCastle. Without the key id and the key the provider mode is off and Monobank is connected with personal
 * tokens only.
 */
@Component
public class MonobankSigner {

    private static final Logger log = LoggerFactory.getLogger(MonobankSigner.class);
    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

    private final String keyId;
    private final PrivateKey key;

    public MonobankSigner(
            @Value("${app.monobank.provider.key-id:}") String keyId,
            @Value("${app.monobank.provider.private-key:}") String privateKey) {
        PrivateKey parsed = null;
        if (!keyId.isBlank() && !privateKey.isBlank()) {
            try {
                parsed = parse(privateKey);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // A broken key must not stop the server: the personal tokens still work
                log.error("The Monobank provider key is not a PKCS#8 EC key; the provider mode is off");
            }
        }
        this.keyId = parsed == null ? "" : keyId.trim();
        this.key = parsed;
    }

    public boolean enabled() {
        return key != null;
    }

    public String keyId() {
        return keyId;
    }

    /** Base64 of the DER-encoded signature of the text. */
    public String sign(String text) {
        if (key == null) {
            throw new IllegalStateException("The Monobank provider mode is off");
        }
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA", BOUNCY_CASTLE);
            signature.initSign(key);
            signature.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing a Monobank request failed", e);
        }
    }

    /**
     * Reads a PKCS#8 PEM, with its lines joined or not; an environment variable often carries it as one line, or
     * with the line breaks written as {@code \n}.
     */
    static PrivateKey parse(String pem) throws GeneralSecurityException {
        String body = pem.replace("\\n", "")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("EC", BOUNCY_CASTLE).generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
