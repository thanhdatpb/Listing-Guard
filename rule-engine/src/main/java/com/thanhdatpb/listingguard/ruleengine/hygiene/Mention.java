package com.thanhdatpb.listingguard.ruleengine.hygiene;

/**
 * One fact a text states (ADR-039): a size, a quantity, a recipient or a product noun. {@code family} says what is compared (the unit
 * of a size, the axis of a recipient, {@code quantity}, {@code noun}); {@code key} is the canonical value ({@code 11 oz}, {@code 2},
 * {@code mom}), so two mentions state the same thing when family and key are equal; {@code text} is what the seller wrote, at
 * {@code start} (inclusive) to {@code end} (exclusive) in UTF-16 code units of the text that was read.
 */
public record Mention(String family, String key, String text, int start, int end) {
}
