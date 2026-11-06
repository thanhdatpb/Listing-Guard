package com.thanhdatpb.listingguard.variation.config;

import java.util.Locale;

/**
 * How well a rule or a value is backed by the marketplace's documentation (ADR-001): {@code VERIFIED} an official page
 * that can be read, {@code SECONDARY} consistent secondary sources, {@code UNVERIFIED} our own reading or heuristic. In
 * the YAML the status is lower case. Keep in sync with {@code ck_variation_check_finding_source_status}.
 */
public enum SourceStatus {
    VERIFIED,
    SECONDARY,
    UNVERIFIED;

    public String yaml() {
        return name().toLowerCase(Locale.ROOT);
    }

    static SourceStatus parse(String where, String value) {
        for (SourceStatus status : values()) {
            if (status.yaml().equals(value)) {
                return status;
            }
        }
        throw new VariationRulesetException(where + " must be 'verified', 'secondary' or 'unverified', got '" + value + "'");
    }
}
