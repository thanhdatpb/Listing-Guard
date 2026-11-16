package com.thanhdatpb.listingguard.client.model;

/**
 * The deployment's bounds of a watchlist (schema {@code TrademarkWatchlistLimits}).
 *
 * @param maxEntries   most entries in one list
 * @param maxAliases   most aliases of one entry
 * @param maxTextChars longest mark or alias, in characters
 * @param maxNoteChars longest note, in characters
 * @param maxTokens    most words in one mark or alias after normalization
 */
public record TrademarkWatchlistLimits(int maxEntries, int maxAliases, int maxTextChars, int maxNoteChars, int maxTokens) {
}
