package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Request to compare two marketplace listing objects; maps preserve marketplace-specific fields. */
public record ListingEditRequest(String marketplace, Object before, Object after,
                                 @JsonProperty("before_variations") VariationFamily beforeVariations,
                                 @JsonProperty("after_variations") VariationFamily afterVariations) {
    public record VariationFamily(VariationParent parent, @JsonProperty("variation_properties") List<String> variationProperties,
                                  List<VariationChild> children) { }
}
