package com.mandi.identity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Random one-time tokens for invitations and password recovery (blueprint 7.1). A token is 256 bits from
 * {@link SecureRandom}, shown to its recipient as URL-safe text. Only the SHA-256 digest of its 32 bytes is ever stored
 * or looked up, so a database copy or a log line contains nothing usable. Never log {@link Issued#raw()}.
 */
public final class OneTimeTokens {

    public static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private OneTimeTokens() {
    }

    /** The raw token (give it to the recipient, never store it) and its digest (store this). */
    public record Issued(String raw, byte[] hash) {
        @Override
        public String toString() {
            return "Issued[raw=<hidden>, hash=<hidden>]";
        }
    }

    public static Issued issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return new Issued(ENCODER.encodeToString(bytes), digest(bytes));
    }

    /**
     * The digest to look up for a token a person presented, or empty when it cannot be one of ours (wrong length or
     * not URL-safe base64): such input never reaches the database.
     */
    public static Optional<byte[]> hashOf(String presented) {
        if (presented == null || presented.length() != 43) return Optional.empty();
        try {
            byte[] bytes = DECODER.decode(presented);
            return bytes.length == TOKEN_BYTES ? Optional.of(digest(bytes)) : Optional.empty();
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
