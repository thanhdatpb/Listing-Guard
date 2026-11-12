package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * The request of {@code POST /api/v1/variation-checks} ({@code docs/input-schema.md}, "Variation checks"): a parent and its
 * children for Amazon, the variation types and the children for Etsy. Not wrapped in {@code listing}.
 *
 * @param marketplace         {@code ETSY} or {@code AMAZON}
 * @param parent              Amazon only (ignored for Etsy), or {@code null}
 * @param variationProperties Etsy only (ignored for Amazon): the names of the variation types, or {@code null}
 * @param children            the children, in order, or {@code null}
 */
public record VariationCheckRequest(
        String marketplace,
        VariationParent parent,
        @JsonProperty("variation_properties") List<String> variationProperties,
        List<VariationChild> children) {

    public VariationCheckRequest {
        Objects.requireNonNull(marketplace, "marketplace");
        variationProperties = variationProperties == null ? null : List.copyOf(variationProperties);
        children = children == null ? null : List.copyOf(children);
    }

    /** An Amazon family: the parent and its children. */
    public static VariationCheckRequest amazon(VariationParent parent, List<VariationChild> children) {
        return new VariationCheckRequest("AMAZON", parent, null, children);
    }

    /** An Etsy listing: the names of its variation types and its children. */
    public static VariationCheckRequest etsy(List<String> variationProperties, List<VariationChild> children) {
        return new VariationCheckRequest("ETSY", null, variationProperties, children);
    }

    /** Never shows SKUs or titles: they are client data. */
    @Override
    public String toString() {
        return "VariationCheckRequest[marketplace=" + marketplace + ", children="
                + (children == null ? 0 : children.size()) + "]";
    }
}
