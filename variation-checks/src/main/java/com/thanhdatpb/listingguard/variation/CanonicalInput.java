package com.thanhdatpb.listingguard.variation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The hash of a submitted variation family (ADR-015). It is SHA-256 over {@link #HASH_FORMAT} and then every field as a
 * line {@code name:byteLength:text}, after Unicode NFC and {@code strip}, in a fixed order: the parent (a line
 * {@code parent:1} or {@code parent:0}, then its sku, product type, theme and title), the variation properties (each,
 * then a line with their count), then each child (sku, parent sku, title, its attributes sorted by name by code point as
 * {@code attribute:keyBytes:key:valueBytes:value}, then a line with their count) and a last line with the number of
 * children. The length prefixes and counts make the serialization unambiguous, and the order of the JSON keys does not
 * matter. Case is kept, so {@code Red} and {@code red} are two inputs. The marketplace is not in the hash: it is a
 * separate part of the cache identity.
 *
 * <p>Only this hash is stored, never the family itself.
 */
public record CanonicalInput(String hash) {

    public static final String HASH_FORMAT = "listing-guard/variation-input/v1";

    private static final Comparator<String> BY_CODE_POINT =
            (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());

    public static CanonicalInput of(VariationInput input) {
        StringBuilder text = new StringBuilder(HASH_FORMAT).append('\n');
        VariationInput.Parent parent = input.parent();
        text.append("parent:").append(parent == null ? 0 : 1).append('\n');
        if (parent != null) {
            field(text, "parent.sku", parent.sku());
            field(text, "parent.product_type", parent.productType());
            field(text, "parent.variation_theme", parent.variationTheme());
            field(text, "parent.title", parent.title());
        }
        for (int i = 0; i < input.variationProperties().size(); i++) {
            field(text, "variation_properties[" + i + "]", input.variationProperties().get(i));
        }
        text.append("variation_properties:").append(input.variationProperties().size()).append('\n');
        for (int i = 0; i < input.children().size(); i++) {
            VariationInput.Child child = input.children().get(i);
            String prefix = "children[" + i + "].";
            field(text, prefix + "sku", child.sku());
            field(text, prefix + "parent_sku", child.parentSku());
            field(text, prefix + "title", child.title());
            attributes(text, child.attributes());
            text.append(prefix).append("attributes:").append(child.attributes().size()).append('\n');
        }
        text.append("children:").append(input.children().size()).append('\n');
        return new CanonicalInput(sha256(text.toString()));
    }

    private static void attributes(StringBuilder text, Map<String, String> attributes) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>();
        attributes.forEach((key, value) -> sorted.add(Map.entry(Texts.canonical(key), Texts.canonical(value))));
        sorted.sort(Map.Entry.<String, String>comparingByKey(BY_CODE_POINT).thenComparing(Map.Entry.comparingByValue(BY_CODE_POINT)));
        for (Map.Entry<String, String> entry : sorted) {
            text.append("attribute:").append(bytes(entry.getKey())).append(':').append(entry.getKey()).append(':')
                    .append(bytes(entry.getValue())).append(':').append(entry.getValue()).append('\n');
        }
    }

    private static void field(StringBuilder text, String name, String value) {
        String canonical = Texts.canonical(value);
        text.append(name).append(':').append(bytes(canonical)).append(':').append(canonical).append('\n');
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
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
