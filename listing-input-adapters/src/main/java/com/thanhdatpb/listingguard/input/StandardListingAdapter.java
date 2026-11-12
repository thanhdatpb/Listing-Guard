package com.thanhdatpb.listingguard.input;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/** Shared boundary used by file and console inputs to keep only fields in the public standard schema. */
public final class StandardListingAdapter {
    private static final Set<String> ETSY = Set.of("title", "description", "tags", "price", "category", "taxonomy_path",
            "materials", "images", "quantity", "who_made", "when_made", "personalization");
    private static final Set<String> AMAZON = Set.of("title", "brand", "bullet_points", "description", "search_terms",
            "product_type", "price", "quantity", "images", "item_highlights", "customization");

    private StandardListingAdapter() { }

    /** Copies an already mapped listing unchanged while discarding fields outside docs/input-schema.md. */
    public static ObjectNode toStandardInput(String marketplace, ObjectNode mapped) {
        Set<String> fields = switch (marketplace.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ETSY" -> ETSY;
            case "AMAZON" -> AMAZON;
            default -> throw new IllegalArgumentException("Unsupported marketplace: " + marketplace);
        };
        ObjectNode result = mapped.deepCopy();
        result.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) result.remove(name);
        });
        return result;
    }
}
