package com.thanhdatpb.listingguard.image;

import java.util.List;
import java.util.Objects;

/** Binary image input for ADR-008. The checker never retains or writes these bytes. */
public record ImageCheckInput(ImageMarketplace marketplace, String profile, List<ImageItem> images) {
    public ImageCheckInput {
        Objects.requireNonNull(marketplace, "marketplace");
        if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile is required");
        profile = profile.strip();
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        for (ImageItem item : images) Objects.requireNonNull(item, "image item");
    }
}
