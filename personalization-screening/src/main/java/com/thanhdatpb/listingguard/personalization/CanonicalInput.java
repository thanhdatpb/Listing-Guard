package com.thanhdatpb.listingguard.personalization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The canonical form of the input and its hash (ADR-016). SHA-256 over {@link #HASH_FORMAT}, then every field as a line
 * {@code name:byteLength:text}: the print profile code and the product type (an absent one is empty), then for each value
 * its label and its text, then a line {@code values:count}. The length prefixes and the count make the serialization
 * unambiguous. The print profile, product type and label are NFC and stripped; the text of a value is NFC and
 * <em>not</em> stripped, so two texts that differ only in a space are two inputs. Case is kept. The marketplace is not in the
 * hash: it is a separate part of the cache identity.
 */
public record CanonicalInput(String hash) {

    public static final String HASH_FORMAT = "listing-guard/personalization-input/v1";

    public static CanonicalInput of(PersonalizationInput input) {
        StringBuilder serialized = new StringBuilder(HASH_FORMAT).append('\n');
        line(serialized, "print_profile", Texts.nfcStrip(input.printProfile()));
        line(serialized, "product_type", Texts.nfcStrip(input.productType()));
        for (int i = 0; i < input.values().size(); i++) {
            PersonalizationInput.Value value = input.values().get(i);
            line(serialized, "values[" + i + "].label", Texts.nfcStrip(value.label()));
            line(serialized, "values[" + i + "].text", Texts.nfc(value.text()));
        }
        serialized.append("values:").append(input.values().size()).append('\n');
        return new CanonicalInput(sha256(serialized.toString()));
    }

    private static void line(StringBuilder serialized, String name, String text) {
        serialized.append(name).append(':').append(text.getBytes(StandardCharsets.UTF_8).length).append(':').append(text)
                .append('\n');
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
