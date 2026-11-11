package com.thanhdatpb.listingguard.trademark.config;

import java.util.List;

/**
 * A scan was asked for a product type that the ruleset does not declare under {@code product_classes.types}, or the
 * ruleset declares none (ADR-009). It is the caller's mistake: the scanner does not fall back to a scan without product
 * classes, because the answer would then not be the one the caller asked for. The API answers it as a 400.
 */
public class UnknownProductTypeException extends IllegalArgumentException {

    private static final int SHOWN_CHARS = 40;

    private final List<String> known;

    public UnknownProductTypeException(String productType, List<String> known) {
        super("Unknown product type '" + printable(productType) + "'; declared product types: " + known);
        this.known = List.copyOf(known);
    }

    /** The product types the ruleset declares, in the order of the file; empty when it declares none. */
    public List<String> known() {
        return known;
    }

    /** What the caller sent, made safe for a message: only plain characters, and not longer than a key can be. */
    private static String printable(String value) {
        String plain = value.replaceAll("[^A-Za-z0-9_.-]", "?");
        return plain.length() <= SHOWN_CHARS ? plain : plain.substring(0, SHOWN_CHARS) + "...";
    }
}
