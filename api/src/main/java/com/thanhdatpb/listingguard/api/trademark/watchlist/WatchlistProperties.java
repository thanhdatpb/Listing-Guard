package com.thanhdatpb.listingguard.api.trademark.watchlist;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The client trademark watchlist (ADR-031). The limits are operational bounds of a deployment, not marketplace values.
 *
 * @param settings     the versioned settings file (minimum level and messages), {@code classpath:} or {@code file:}
 * @param maxEntries   most entries in one client's list
 * @param maxAliases   most aliases of one entry
 * @param maxTextChars longest mark or alias, in characters (code points)
 * @param maxNoteChars longest note, in characters (code points)
 * @param maxTokens    most normalized words in one mark or alias
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.watchlist")
public record WatchlistProperties(
        @DefaultValue("classpath:watchlist/trademark-watchlist-2026-11.yaml") String settings,
        @DefaultValue("500") int maxEntries,
        @DefaultValue("20") int maxAliases,
        @DefaultValue("100") int maxTextChars,
        @DefaultValue("500") int maxNoteChars,
        @DefaultValue("12") int maxTokens) {

    public WatchlistProperties {
        if (maxEntries < 1 || maxAliases < 0 || maxTextChars < 1 || maxNoteChars < 1 || maxTokens < 1) {
            throw new IllegalArgumentException("listing-guard.trademark.watchlist: max-entries, max-text-chars, max-note-chars "
                    + "and max-tokens must be at least 1, max-aliases at least 0");
        }
        if (maxTextChars > 400) {
            throw new IllegalArgumentException("listing-guard.trademark.watchlist.max-text-chars cannot be above 400 (the column)");
        }
    }
}
