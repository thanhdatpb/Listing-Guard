package com.thanhdatpb.listingguard.trademark.watchlist;

import java.util.List;
import java.util.Objects;

/**
 * One entry of a client's trademark watchlist (ADR-031): a mark the client wants reported wherever it appears, with the
 * variants it is also written as. Only enabled entries reach the scanner.
 *
 * @param id           the entry's stable id, as stored
 * @param mark         the mark as the client wrote it; shown in messages and evidence
 * @param aliases      other ways the mark is written (nicknames, abbreviations), as the client wrote them
 * @param niceClasses  the Nice classes the client noted, ascending; shown, not used to change a level
 * @param serialNumber a USPTO serial number (eight digits), or null
 * @param note         why the entry is listed (for example a claim the client received), or null
 */
public record WatchlistEntry(String id, String mark, List<String> aliases, List<Integer> niceClasses, String serialNumber,
                             String note) {

    public WatchlistEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mark, "mark");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        niceClasses = niceClasses == null ? List.of() : List.copyOf(niceClasses);
    }

    /** The source id a serial number stands for, so that the ruleset's source links apply; empty without one. */
    public List<String> sourceIds() {
        return serialNumber == null ? List.of() : List.of("uspto:" + serialNumber);
    }
}
