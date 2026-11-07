package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The deterministic fingerprint of everything that can change this pack's findings (ADR-010, ADR-016): pack id, ruleset
 * version and content hash, the versions of the canonical input and of the rules, the marketplace, and the fingerprint of
 * the trademark scan (the scanner's ruleset, algorithms, catalog, PostgreSQL major version and {@code pg_trgm} version), or
 * {@link #NOT_CONFIGURED} when no catalog is served. The descriptor is a text of {@code key=value} lines in a fixed order,
 * versioned by {@link #FORMAT}; {@code value} is its SHA-256 and is the {@code analysisVersion} of the stored result.
 */
public record AnalysisFingerprint(String descriptor, String value) {

    public static final String FORMAT = "listing-guard/personalization-fingerprint/v1";

    /** The trademark component when no catalog is served. */
    public static final String NOT_CONFIGURED = "not-configured";

    public static AnalysisFingerprint of(PersonalizationRuleset ruleset, AlgorithmVersions versions, String marketplace,
                                         String trademarkFingerprint) {
        StringBuilder descriptor = new StringBuilder(FORMAT).append('\n');
        line(descriptor, "pack_id", ruleset.packId());
        line(descriptor, "ruleset_version", ruleset.version());
        line(descriptor, "ruleset_hash", ruleset.contentHash());
        line(descriptor, "input_version", versions.input());
        line(descriptor, "rules_version", versions.rules());
        line(descriptor, "marketplace", marketplace);
        line(descriptor, "trademark_fingerprint", trademarkFingerprint == null ? NOT_CONFIGURED : trademarkFingerprint);
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
