package com.thanhdatpb.listingguard.trademark.watchlist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The enabled entries of one client's watchlist, with the settings they are matched under (ADR-031). Immutable.
 *
 * <p>{@link #contentHash()} is the SHA-256 of a canonical serialization of the entries, sorted by id: id, mark, aliases (in
 * order), Nice classes, serial number and note, each length-prefixed so no value can imitate another. It does not depend on the
 * order the entries were given in, and it is part of the analysis fingerprint only when there is at least one entry.
 */
public record Watchlist(WatchlistSettings settings, List<WatchlistEntry> entries) {

    /** The version of the watchlist matching algorithm; a change of how forms are matched needs a new value. */
    public static final String MATCHER = "watchlist-1";

    /** No watchlist: the scan is exactly the scan without one. */
    public static final Watchlist EMPTY = new Watchlist(null, List.of());

    public Watchlist {
        entries = entries == null ? List.of() : entries.stream().sorted(Comparator.comparing(WatchlistEntry::id)).toList();
        if (!entries.isEmpty() && settings == null) {
            throw new IllegalArgumentException("A watchlist with entries needs its settings");
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String contentHash() {
        StringBuilder canonical = new StringBuilder("listing-guard/trademark-watchlist/v1\n");
        for (WatchlistEntry entry : entries) {
            field(canonical, entry.id());
            field(canonical, entry.mark());
            canonical.append(entry.aliases().size()).append(':');
            entry.aliases().forEach(alias -> field(canonical, alias));
            field(canonical, entry.niceClasses().toString());
            field(canonical, entry.serialNumber());
            field(canonical, entry.note());
            canonical.append('\n');
        }
        return sha256(canonical.toString());
    }

    /**
     * What the analysis fingerprint must add for this watchlist: nothing when it is empty, so a client without one keeps its
     * {@code analysisVersion}; otherwise the entries' hash, the settings and the matcher version.
     */
    public Map<String, String> fingerprintComponents() {
        if (isEmpty()) {
            return Map.of();
        }
        return Map.of("watchlist_sha256", contentHash(),
                "watchlist_settings", settings.fingerprintComponent(),
                "watchlist_matcher", MATCHER);
    }

    private static void field(StringBuilder out, String value) {
        if (value == null) {
            out.append("-;");
        } else {
            out.append(value.length()).append(':').append(value).append(';');
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
