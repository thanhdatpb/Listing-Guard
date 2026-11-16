package com.thanhdatpb.listingguard.client.model;

import java.util.List;
import java.util.Objects;

/**
 * One entry of {@link TrademarkWatchlistRequest} (schema {@code TrademarkWatchlistEntryInput}).
 *
 * @param mark         the mark; after normalization (case, accents, punctuation) no two marks or aliases of a list may be equal
 * @param aliases      other ways it is written, or {@code null}
 * @param niceClasses  Nice classes 1 to 45, or {@code null}
 * @param serialNumber a USPTO serial number (eight digits), or {@code null}
 * @param note         why it is listed, or {@code null}
 * @param enabled      {@code null} or {@code true} to scan for it, {@code false} to keep it without scanning
 */
public record TrademarkWatchlistEntryInput(String mark, List<String> aliases, List<Integer> niceClasses, String serialNumber,
                                           String note, Boolean enabled) {

    public TrademarkWatchlistEntryInput {
        Objects.requireNonNull(mark, "mark");
    }

    /** An enabled entry with only a mark. */
    public static TrademarkWatchlistEntryInput of(String mark) {
        return new TrademarkWatchlistEntryInput(mark, null, null, null, null, null);
    }

    /** The same entry with aliases. */
    public TrademarkWatchlistEntryInput withAliases(List<String> aliases) {
        return new TrademarkWatchlistEntryInput(mark, aliases, niceClasses, serialNumber, note, enabled);
    }

    /** The same entry with a note (why it is listed). */
    public TrademarkWatchlistEntryInput withNote(String note) {
        return new TrademarkWatchlistEntryInput(mark, aliases, niceClasses, serialNumber, note, enabled);
    }
}
