package com.thanhdatpb.listingguard.api.listing;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.fasterxml.jackson.databind.JsonNode;

/** Converts a marketplace-specific raw listing payload into the internal {@link Listing}. */
public interface Normalizer {

    /** Marketplace code this normalizer handles, e.g. {@code ETSY}. */
    String marketplace();

    /**
     * @throws IllegalArgumentException if {@code raw} is not a JSON object
     */
    Listing normalize(JsonNode raw);
}
