package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The deterministic fingerprint of everything that can change this pack's findings (ADR-010): pack id, ruleset
 * version and content hash, and the versions of the normalizer, matcher and scorer. Components that live outside
 * this module (the catalog version and hash, the PostgreSQL major version, the {@code pg_trgm} extension version,
 * the marketplace) are passed in as {@code externalComponents} by the caller that knows them; this module only
 * guarantees that every one of them takes part.
 *
 * <p>The descriptor is a text of {@code key=value} lines in a fixed order, versioned by {@link #FORMAT}. Keys and
 * values of external components are sorted by key. {@code value} is the SHA-256 of the descriptor.
 */
public record AnalysisFingerprint(String descriptor, String value) {

    public static final String FORMAT = "listing-guard/analysis-fingerprint/v1";

    public static AnalysisFingerprint of(TrademarkRuleset ruleset, AlgorithmVersions versions,
                                         Map<String, String> externalComponents) {
        StringBuilder descriptor = new StringBuilder(FORMAT).append('\n');
        line(descriptor, "pack_id", ruleset.packId());
        line(descriptor, "ruleset_version", ruleset.version());
        line(descriptor, "ruleset_hash", ruleset.contentHash());
        line(descriptor, "normalization_version", versions.normalization());
        line(descriptor, "matcher_version", versions.matcher());
        line(descriptor, "scoring_version", versions.scoring());
        new TreeMap<>(externalComponents).forEach((key, component) -> {
            if (!key.matches("[a-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("External component name must match [a-z0-9_.-]{1,64}: " + key);
            }
            line(descriptor, "external." + key, component);
        });
        return new AnalysisFingerprint(descriptor.toString(), sha256(descriptor.toString()));
    }

    private static void line(StringBuilder descriptor, String key, String value) {
        if (value == null || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Fingerprint component '" + key + "' must be a single line");
        }
        descriptor.append(key).append('=').append(value).append('\n');
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
