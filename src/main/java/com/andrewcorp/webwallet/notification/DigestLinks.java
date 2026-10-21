package com.andrewcorp.webwallet.notification;

import com.andrewcorp.webwallet.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The unsubscribe links of the digest emails. A link names the user and carries a signature of the server, so that
 * it works without signing in for that user only, and needs nothing stored.
 */
@Component
public class DigestLinks {

    public static final String UNSUBSCRIBE_PATH = "/api/v1/notifications/unsubscribe";
    public static final String PAGE_PATH = "/unsubscribe";

    private final SecretKey key;
    private final AppProperties properties;
    private final String publicUrl;

    public DigestLinks(
            SecretKey jwtSecretKey, AppProperties properties, @Value("${app.public-url:}") String publicUrl) {
        this.key = jwtSecretKey;
        this.properties = properties;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    /** For the List-Unsubscribe header: one POST unsubscribes, as RFC 8058 has it. */
    public String oneClick(Long userId) {
        String base = publicUrl.isEmpty() ? properties.frontendUrl("") : publicUrl;
        return base + UNSUBSCRIBE_PATH + query(userId);
    }

    /** For the footer of the email: the page that unsubscribes and offers to subscribe again. */
    public String page(Long userId) {
        return properties.frontendUrl(PAGE_PATH) + query(userId);
    }

    public boolean valid(Long userId, String token) {
        return userId != null
                && token != null
                && MessageDigest.isEqual(
                        token(userId).getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII));
    }

    private String query(Long userId) {
        return "?user=" + userId + "&token=" + token(userId);
    }

    private String token(Long userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            // A purpose of its own, so that the signature means nothing elsewhere
            byte[] signature = mac.doFinal(("digest-unsubscribe:" + userId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
