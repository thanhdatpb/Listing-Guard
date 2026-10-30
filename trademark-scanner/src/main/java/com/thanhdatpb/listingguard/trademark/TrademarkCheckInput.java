package com.thanhdatpb.listingguard.trademark;

import java.util.List;

/**
 * The text of a listing that the scanner reads (ADR-009). A null field is treated as empty. The marketplace is not
 * part of the input: it belongs to the cache identity, not to what the seller wrote. The category is hashed with the
 * rest but only scanned when the ruleset lists it in {@code matching.scan_fields}.
 */
public record TrademarkCheckInput(String title, List<String> tags, String description, String brand,
                                  String shopName, String category) {

    public TrademarkCheckInput {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static TrademarkCheckInput ofTitle(String title) {
        return new TrademarkCheckInput(title, List.of(), null, null, null, null);
    }
}
