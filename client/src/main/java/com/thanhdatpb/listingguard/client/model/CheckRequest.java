package com.thanhdatpb.listingguard.client.model;

import java.util.Objects;

/**
 * The request of {@code POST /api/v1/checks}: a marketplace code and the listing in the marketplace's standard input format
 * ({@code docs/input-schema.md}: Etsy Open API v3 style snake_case fields, or the Amazon simple format). Send what the seller
 * wrote; the API cleans and checks it.
 *
 * @param marketplace {@code ETSY} or {@code AMAZON} (case-insensitive; the API refuses an unknown one)
 * @param listing     the listing: a {@code Map}, a record or any object Jackson writes as the standard input, or the tree
 *                    made by {@link #ofJson(String, String)}
 */
public record CheckRequest(String marketplace, Object listing) {

    public CheckRequest {
        Objects.requireNonNull(marketplace, "marketplace");
        Objects.requireNonNull(listing, "listing");
    }

    /** A request for a listing given as an object. */
    public static CheckRequest of(String marketplace, Object listing) {
        return new CheckRequest(marketplace, listing);
    }

    /**
     * A request for a listing given as JSON text; it must be exactly one JSON object and is sent with its numbers unchanged.
     *
     * @throws IllegalArgumentException when it is not (the message never quotes the text)
     */
    public static CheckRequest ofJson(String marketplace, String listingJson) {
        return new CheckRequest(marketplace, ModelJson.object(listingJson));
    }

    /** Never shows the listing: it is client data. */
    @Override
    public String toString() {
        return "CheckRequest[marketplace=" + marketplace + ", listing=<hidden>]";
    }
}
