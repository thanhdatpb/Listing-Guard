package com.thanhdatpb.listingguard.client.model;

/**
 * The watchlist a trademark check used (schema {@code TrademarkWatchlistUsage}).
 *
 * @param revision the watchlist's revision when the check ran
 * @param version  the hash of the enabled entries; part of the check's {@code analysisVersion}
 * @param entries  how many enabled entries were looked for
 */
public record WatchlistUsage(long revision, String version, int entries) {
}
