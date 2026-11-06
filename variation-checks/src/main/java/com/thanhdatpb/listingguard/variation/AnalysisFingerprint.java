package com.thanhdatpb.listingguard.variation;

import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The deterministic fingerprint of everything that can change this pack's findings (ADR-010, ADR-015): pack id, ruleset
 * version and content hash, the version of the canonical input format, the version of the comparison logic, and the
 * marketplace. There is no external dataset. The descriptor is a text of {@code key=value} lines in a fixed order,
 * versioned by {@link #FORMAT}; {@code value} is its SHA-256 and is the {@code analysisVersion} of the stored result.
 */
public record AnalysisFingerprint(String descriptor, String value) {

    public static final String FORMAT = "listing-guard/variation-fingerprint/v1";

    public static AnalysisFingerprint of(VariationRuleset ruleset, AlgorithmVersions versions, String marketplace) {
        StringBuilder descriptor = new StringBuilder(FORMAT).append('\n');
        line(descriptor, "pack_id", ruleset.packId());
        line(descriptor, "ruleset_version", ruleset.version());
        line(descriptor, "ruleset_hash", ruleset.contentHash());
        line(descriptor, "input_version", versions.input());
        line(descriptor, "rules_version", versions.rules());
        line(descriptor, "marketplace", marketplace);
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
