package com.thanhdatpb.listingguard.trademark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The submitted text in canonical form and its hash (ADR-009). Canonical means: fixed field
 * order (title, tags, bullet points, description, brand, shop name, search terms, category; the lists in the order
 * sent), Unicode NFC, surrounding whitespace removed
 * ({@code String.strip}). It is the text before any homoglyph, leet, diacritic or punctuation normalization, so the
 * hash tells apart submissions that only normalize to the same match form. Evidence offsets refer to these
 * strings, in UTF-16 code units.
 *
 * <p>A blank tag or bullet point stays in its position (as an empty string), so {@code tags[i]} and
 * {@code bullet_points[i]} always mean the i-th item the caller sent. The hash is SHA-256 over {@link #HASH_FORMAT},
 * then every field as a line {@code name:byteLength:text}, with a line {@code name:count} after the last item of
 * each list; the length prefix keeps the serialization unambiguous.
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
        StringBuilder serialized = new StringBuilder(HASH_FORMAT).append('\n');
        add(fields, serialized, "title", input.title());
        addList(fields, serialized, "tags", input.tags());
        addList(fields, serialized, "bullet_points", input.bulletPoints());
        add(fields, serialized, "description", input.description());
        add(fields, serialized, "brand", input.brand());
        add(fields, serialized, "shop_name", input.shopName());
        add(fields, serialized, "search_terms", input.searchTerms());
        add(fields, serialized, "category", input.category());
        return new CanonicalInput(fields, sha256(serialized.toString()));
    }

    private static void addList(List<Field> fields, StringBuilder serialized, String name, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            add(fields, serialized, name + "[" + i + "]", values.get(i));
        }
        // the count closes the list, so the serialization says where it ends
        serialized.append(name).append(':').append(values.size()).append('\n');
    }

    private static void add(List<Field> fields, StringBuilder serialized, String name, String value) {
        String text = canonical(value);
        fields.add(new Field(name, text));
        serialized.append(name).append(':').append(text.getBytes(StandardCharsets.UTF_8).length).append(':')
                .append(text).append('\n');
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
