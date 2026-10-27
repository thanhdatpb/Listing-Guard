package com.thanhdatpb.listingguard.core.listing;

import java.util.List;

/**
 * Marketplace-neutral listing. Scalar fields are null when absent in the source; list fields are
 * never null. Values are normalized (trimmed) but never truncated or corrected: checking limits is
 * the job of the rule engine, not of the schema.
 */
public record Listing(
        String title,
        String description,
        List<String> tags,
        Money price,
        String category,
        List<String> materials,
        List<String> images,
        Integer quantity,
        String whoMade,
        String whenMade) {

    public Listing {
        tags = tags == null ? List.of() : List.copyOf(tags);
        materials = materials == null ? List.of() : List.copyOf(materials);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
