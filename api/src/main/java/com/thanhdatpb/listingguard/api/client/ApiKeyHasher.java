package com.thanhdatpb.listingguard.api.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stored form of an API key. Issued keys carry 256 random bits, so a plain SHA-256 is enough: there is nothing
 * to guess, and a database lookup by digest does not leak anything useful through timing.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static String sha256Hex(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
