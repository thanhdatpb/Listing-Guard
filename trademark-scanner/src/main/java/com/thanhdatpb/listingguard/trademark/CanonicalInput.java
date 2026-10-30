package com.thanhdatpb.listingguard.trademark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The submitted text in canonical form and its hash (ADR-009). Canonical means: fixed field order (title, tags in
 * the order given, description, brand, shop name, category), Unicode NFC, surrounding whitespace removed
 * ({@code String.strip}). It is the text before any homoglyph, leet, diacritic or punctuation normalization, so the
 * hash tells apart submissions that only normalize to the same match form. Evidence offsets refer to these
 * strings, in UTF-16 code units.
 *
 * <p>A blank tag stays in its position (as an empty string), so {@code tags[i]} always means the i-th tag the
 * caller sent. The hash is SHA-256 over {@link #HASH_FORMAT}, then every field as {@code name:byteLength:text}
 * and a line, with the tag count before the first tag; the length prefix keeps the serialization unambiguous.
 */
public record CanonicalInput(List<Field> fields, String hash) {

    public static final String HASH_FORMAT = "listing-guard/trademark-input/v1";

    /** One canonical field: {@code title}, {@code tags[0]}, {@code description}, {@code brand}, ... */
    public record Field(String name, String text) {
    }

    public CanonicalInput {
        fields = List.copyOf(fields);
    }

    public static CanonicalInput of(TrademarkCheckInput input) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("title", canonical(input.title())));
        for (int i = 0; i < input.tags().size(); i++) {
            fields.add(new Field("tags[" + i + "]", canonical(input.tags().get(i))));
        }
        fields.add(new Field("description", canonical(input.description())));
        fields.add(new Field("brand", canonical(input.brand())));
        fields.add(new Field("shop_name", canonical(input.shopName())));
        fields.add(new Field("category", canonical(input.category())));

        StringBuilder serialized = new StringBuilder(HASH_FORMAT).append('\n');
        for (Field field : fields) {
            if (field.name().equals("description")) {
                // the tag count closes the tags, so the serialization says where they end
                serialized.append("tags:").append(input.tags().size()).append('\n');
            }
            serialized.append(field.name()).append(':')
                    .append(field.text().getBytes(StandardCharsets.UTF_8).length).append(':')
                    .append(field.text()).append('\n');
        }
        return new CanonicalInput(fields, sha256(serialized.toString()));
    }

    /** NFC, then strip. Null is empty. */
    static String canonical(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
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
