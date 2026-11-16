package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;

/**
 * A client's trademark watchlist (schema {@code TrademarkWatchlist}; ADR-031): the answer of {@code GET} and {@code PUT
 * /api/v1/trademark-watchlist}. Every trademark check of the client looks for its enabled entries next to the catalog.
 *
 * @param revision  grows by one at every change, 0 when the client never had a list; send it back as the expected revision
 * @param version   the hash of the enabled entries (the {@code watchlist.version} of a check), or {@code null} when none is enabled
 * @param updatedAt when the list last changed (UTC), or {@code null} when it never existed
 * @param entries   every entry, enabled or not, in the client's order
 * @param limits    the deployment's bounds
 */
public record TrademarkWatchlist(long revision, String version, Instant updatedAt, List<TrademarkWatchlistEntry> entries,
                                 TrademarkWatchlistLimits limits) {

    public TrademarkWatchlist {
        entries = Copies.list(entries);
    }
}
