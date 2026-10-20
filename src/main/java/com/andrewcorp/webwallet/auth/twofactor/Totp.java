package com.andrewcorp.webwallet.auth.twofactor;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238) as authenticator apps generate them: HMAC-SHA1, 6 digits, 30 seconds.
 */
public final class Totp {

    static final int DIGITS = 6;
    static final long PERIOD_SECONDS = 30;
    /** Codes of the neighbouring time steps are accepted too, for clocks that are a little off. */
    private static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new 160-bit secret in Base32, the form authenticator apps take. */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /** The link a QR code carries; authenticator apps show the issuer and the account name. */
    public static String uri(String issuer, String account, String secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits="
                + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    public static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * Returns the time step the code belongs to, or -1 when it matches none of the steps around {@code now}. Steps up
     * to {@code lastUsedStep} are skipped, so that a code that was used once is rejected.
     */
    public static long verify(String secret, String code, Instant now, Long lastUsedStep) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (digits.length() != DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        byte[] key = unbase32(secret);
        long current = step(now);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (constantTimeEquals(code(key, step), digits)) {
                return step;
            }
        }
        return -1;
    }

    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not Base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
