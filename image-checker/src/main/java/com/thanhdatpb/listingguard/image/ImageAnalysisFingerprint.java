package com.thanhdatpb.listingguard.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** Includes ruleset bytes, deterministic algorithm revisions and runtime imaging/color providers (ADR-010). */
public record ImageAnalysisFingerprint(String descriptor, String value) {
    public static final String FORMAT = "listing-guard/image-analysis-fingerprint/v1";
    public static ImageAnalysisFingerprint of(ImageRuleset ruleset) {
        TreeMap<String, String> runtime = ImageIoRuntime.fingerprintComponents();
        StringBuilder d = new StringBuilder(FORMAT).append('\n')
                .append("pack_id=").append(ruleset.packId()).append('\n')
                .append("marketplace=").append(ruleset.marketplace()).append('\n')
                .append("ruleset_version=").append(ruleset.version()).append('\n')
                .append("ruleset_hash=").append(ruleset.contentHash()).append('\n')
                .append("input_hash_format=").append(CanonicalImageInput.FORMAT).append('\n')
                .append("exif_orientation=exif-orientation-v1\n")
                .append("icc_conversion=java2d-srgb-v1\n")
                .append("alpha_composite=white-v1\n")
                .append("blur=variance-of-laplacian-v1\n")
                .append("edge_scan=perimeter-pixels-v1\n")
                .append("occupancy=nonwhite-bounding-box-v1\n");
        runtime.forEach((key, val) -> d.append("runtime.").append(key).append('=').append(val).append('\n'));
        try {
            String descriptor = d.toString();
            return new ImageAnalysisFingerprint(descriptor, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint image analysis", e); }
    }
}
