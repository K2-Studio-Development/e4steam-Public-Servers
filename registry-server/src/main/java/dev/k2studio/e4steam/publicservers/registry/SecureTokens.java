package dev.k2studio.e4steam.publicservers.registry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Token generation and one-way comparisons shared by the registry boundaries. */
public final class SecureTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private SecureTokens() {
    }

    public static String random(String prefix, int bytes) {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9]*\\.") || bytes < 16 || bytes > 64) {
            throw new IllegalArgumentException("invalid token request");
        }
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return prefix + URL_ENCODER.encodeToString(value);
    }

    public static byte[] randomBytes(int bytes) {
        if (bytes < 16 || bytes > 64) throw new IllegalArgumentException("invalid key size");
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return value;
    }

    public static byte[] sha256(String value) {
        if (value == null) throw new NullPointerException("value");
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static byte[] hmac(byte[] key, String value) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("HMAC key is too short");
        if (value == null) throw new NullPointerException("value");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.clone(), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    public static boolean equal(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }

    public static String base64(byte[] value) {
        return URL_ENCODER.encodeToString(value);
    }

    public static byte[] decodeBase64(String value, int maximumBytes) {
        if (value == null || value.isBlank() || value.length() > maximumBytes * 2) {
            throw new IllegalArgumentException("invalid base64 value");
        }
        try {
            byte[] decoded = URL_DECODER.decode(value);
            if (decoded.length > maximumBytes) throw new IllegalArgumentException("base64 value is too large");
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid base64 value", failure);
        }
    }
}
