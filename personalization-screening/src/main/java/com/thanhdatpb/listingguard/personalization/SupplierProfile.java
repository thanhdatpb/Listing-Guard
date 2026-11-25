package com.thanhdatpb.listingguard.personalization;

import java.util.regex.Pattern;

/**
 * The identity of an imported POD print profile (ADR-048) and the length limit it states for a product. The screener reads the
 * limit in place of the length limit of the ruleset's print profile; the character classes stay the ruleset's. The identity
 * (id, version and the SHA-256 of the released resource) and the limit take part in the analysis fingerprint, so a screening of
 * the same text against another profile or another version of it never shares a cache identity with this one.
 *
 * @param id the product type of the profile, as in its resource name
 * @param version the version of the profile, from 1
 * @param sha256 the SHA-256 (lowercase hex) of the released resource, as the Console shows it
 * @param maxChars the longest text the supplier states can be printed, in code points, from 1
 */
public record SupplierProfile(String id, int version, String sha256, int maxChars) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public SupplierProfile {
        if (id == null || !ID.matcher(id).matches() || version < 1 || sha256 == null || !SHA_256.matcher(sha256).matches()
                || maxChars < 1) {
            throw new IllegalArgumentException("Invalid supplier profile identity or limit");
        }
    }

    /** {@code id-vVersion}, the name of the released resource without its extension. */
    public String key() {
        return id + "-v" + version;
    }
}
