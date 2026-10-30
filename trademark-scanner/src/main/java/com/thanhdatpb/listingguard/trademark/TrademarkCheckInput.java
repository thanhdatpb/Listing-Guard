package com.thanhdatpb.listingguard.trademark;

import java.util.List;

/**
 * The text of a listing that the scanner reads (ADR-009): Etsy tags and the Amazon bullet points and search terms
 * are all optional. A null field is treated as empty. The marketplace is not
 * part of the input: it belongs to the cache identity, not to what the seller wrote. The category is hashed with the
 * rest but only scanned when the ruleset lists it in {@code matching.scan_fields}.
 */
public record TrademarkCheckInput(String title, List<String> tags, List<String> bulletPoints, String description,
                                  String brand, String shopName, String searchTerms, String category) {

    public TrademarkCheckInput {
        tags = tags == null ? List.of() : List.copyOf(tags);
        bulletPoints = bulletPoints == null ? List.of() : List.copyOf(bulletPoints);
    }

    /** The fields every marketplace has; the Amazon bullet points and search terms are empty. */
    public TrademarkCheckInput(String title, List<String> tags, String description, String brand, String shopName,
                               String category) {
        this(title, tags, List.of(), description, brand, shopName, null, category);
    }

    public static TrademarkCheckInput ofTitle(String title) {
        return new TrademarkCheckInput(title, List.of(), null, null, null, null);
    }
}
