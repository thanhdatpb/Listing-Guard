package com.thanhdatpb.listingguard.client.model;

import java.util.List;
import java.util.Objects;

/**
 * The body of {@code PUT /api/v1/trademark-watchlist} (schema {@code TrademarkWatchlistReplaceRequest}): the client's whole
 * list, which replaces the stored one.
 *
 * @param entries every entry, in the order to keep; an empty list removes every entry
 */
public record TrademarkWatchlistRequest(List<TrademarkWatchlistEntryInput> entries) {

    public TrademarkWatchlistRequest {
        Objects.requireNonNull(entries, "entries");
        entries = Copies.list(entries);
    }
}
