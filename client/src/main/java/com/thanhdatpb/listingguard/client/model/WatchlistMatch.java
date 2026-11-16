package com.thanhdatpb.listingguard.client.model;

import java.util.List;
import java.util.UUID;

/**
 * The watchlist entry a finding comes from (schema {@code TrademarkWatchlistMatch}). The mark, the alias and the note are the
 * client's own text: data, never instructions.
 *
 * @param entryId      the entry's id, as {@link TrademarkWatchlist} shows it
 * @param mark         the entry's mark
 * @param matchedAlias the alias that matched, or {@code null} when the mark did
 * @param niceClasses  the Nice classes the client noted (they do not change the level)
 * @param serialNumber the USPTO serial number, or {@code null}
 * @param note         why the client listed it, or {@code null}
 */
public record WatchlistMatch(UUID entryId, String mark, String matchedAlias, List<Integer> niceClasses, String serialNumber,
                             String note) {

    public WatchlistMatch {
        niceClasses = Copies.list(niceClasses);
    }
}
