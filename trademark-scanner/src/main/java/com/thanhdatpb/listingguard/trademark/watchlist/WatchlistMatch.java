package com.thanhdatpb.listingguard.trademark.watchlist;

import java.util.List;

/**
 * Which watchlist entry a finding comes from (ADR-031), part of its evidence.
 *
 * @param entryId      the entry's id
 * @param mark         the entry's mark
 * @param matchedAlias the alias that matched, null when the mark itself matched
 * @param niceClasses  the entry's Nice classes
 * @param serialNumber the entry's serial number, or null
 * @param note         the entry's note, or null
 */
public record WatchlistMatch(String entryId, String mark, String matchedAlias, List<Integer> niceClasses, String serialNumber,
                             String note) {

    public WatchlistMatch {
        niceClasses = niceClasses == null ? List.of() : List.copyOf(niceClasses);
    }

    public static WatchlistMatch of(WatchlistEntry entry, String alias) {
        return new WatchlistMatch(entry.id(), entry.mark(), alias, entry.niceClasses(), entry.serialNumber(), entry.note());
    }
}
