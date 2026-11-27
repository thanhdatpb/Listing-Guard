package com.thanhdatpb.listingguard.api.workspace;

import java.util.Map;
import java.util.UUID;

/**
 * How the SKUs of a clone are made (ADR-062 §4): typed one by one, or from the old SKU by a pattern. The pattern is: replace a string in
 * the old SKU (a plain string, never a regular expression), put a prefix before and a suffix after, then, when asked, a running number
 * (from the first number, padded with zeros to a width) after the suffix. The number goes to the listings in the order of the clone, so
 * {@code suffix "-C"} with numbering from 1 and width 2 makes {@code ABC-C01}, {@code ABC-C02}. Only text is made here: whether a SKU is
 * empty, too long or already used is {@link ListingCloner}'s check, made on the whole list.
 */
public record SkuPlan(Mode mode, String prefix, String suffix, String find, String replace, Integer numberFrom, int numberWidth,
                      Map<UUID, String> manual) {

    public enum Mode {
        MANUAL, PATTERN
    }

    public SkuPlan {
        mode = mode == null ? Mode.PATTERN : mode;
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
        find = find == null ? "" : find;
        replace = replace == null ? "" : replace;
        numberWidth = Math.max(0, Math.min(numberWidth, 12));
        manual = manual == null ? Map.of() : Map.copyOf(manual);
    }

    /** A pattern that keeps every SKU as it is: the starting point of the page, so the preview shows what has to be changed. */
    public static SkuPlan same() {
        return new SkuPlan(Mode.PATTERN, "", "", "", "", null, 0, Map.of());
    }

    /** The new SKU of the listing {@code id}, whose SKU was {@code old} and which is number {@code index} (from 0) of the clone. */
    public String apply(UUID id, String old, int index) {
        if (mode == Mode.MANUAL) {
            return manual.getOrDefault(id, "").strip();
        }
        String base = find.isEmpty() ? old : old.replace(find, replace);
        StringBuilder sku = new StringBuilder(prefix).append(base).append(suffix);
        if (numberFrom != null) {
            String number = Long.toString((long) numberFrom + index);
            sku.append(numberWidth > number.length() ? "0".repeat(numberWidth - number.length()) + number : number);
        }
        return sku.toString().strip();
    }
}
