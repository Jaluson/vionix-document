package com.vionix.backend.common.security;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;

    private Passwords() {
    }

    public static PasswordHash hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return new PasswordHash(derive(password, salt), Base64.getEncoder().encodeToString(salt));
    }

    public static boolean verifyPassword(String password, String expectedHash, String salt) {
        if (password == null || expectedHash == null || salt == null) {
            return false;
        }
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        byte[] actual = Base64.getDecoder().decode(derive(password, saltBytes));
        byte[] expected = Base64.getDecoder().decode(expectedHash);
        return MessageDigest.isEqual(actual, expected);
    }

    public static String randomToken() {
        byte[] token = new byte[48];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute SHA-256", exception);
        }
    }

    public static byte[] hmacSha256(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }

    private static String derive(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash password", exception);
        }
    }

    public record PasswordHash(String hash, String salt) {
    }
}
