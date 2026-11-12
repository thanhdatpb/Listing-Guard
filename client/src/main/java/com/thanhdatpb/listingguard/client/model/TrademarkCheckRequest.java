package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * The request of {@code POST /api/v1/trademark-checks}: the envelope of {@link CheckRequest} plus an optional
 * {@code product_type}.
 *
 * @param marketplace {@code ETSY} or {@code AMAZON}
 * @param listing     the listing, as for {@link CheckRequest}
 * @param productType optional: one of the product types the active trademark ruleset declares ({@code apparel}, {@code mug},
 *                    ...), written exactly as declared; sent unchanged. An undeclared one is refused with
 *                    {@code 400 unknown-product-type}. It is not the Amazon {@code product_type} of the listing.
 */
public record TrademarkCheckRequest(
        String marketplace,
        Object listing,
        @JsonProperty("product_type") String productType) {

    public TrademarkCheckRequest {
        Objects.requireNonNull(marketplace, "marketplace");
        Objects.requireNonNull(listing, "listing");
    }

    /** A request without a product type. */
    public static TrademarkCheckRequest of(String marketplace, Object listing) {
        return new TrademarkCheckRequest(marketplace, listing, null);
    }

    /** A request for a listing given as JSON text (exactly one JSON object). */
    public static TrademarkCheckRequest ofJson(String marketplace, String listingJson) {
        return new TrademarkCheckRequest(marketplace, ModelJson.object(listingJson), null);
    }

    /** The same request with a product type ({@code null} for none). */
    public TrademarkCheckRequest withProductType(String productType) {
        return new TrademarkCheckRequest(marketplace, listing, productType);
    }

    /** Never shows the listing: it is client data. */
    @Override
    public String toString() {
        return "TrademarkCheckRequest[marketplace=" + marketplace + ", productType=" + productType + ", listing=<hidden>]";
    }
}
