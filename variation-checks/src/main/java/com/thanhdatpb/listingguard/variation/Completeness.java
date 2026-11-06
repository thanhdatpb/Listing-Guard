package com.thanhdatpb.listingguard.variation;

/**
 * Whether every rule ran. {@code INCOMPLETE} keeps the findings of the rules that succeeded and names the others; it is
 * never reported as a clean result (ADR-010, ADR-015). Keep in sync with {@code ck_variation_check_completeness}.
 */
public enum Completeness {
    COMPLETE,
    INCOMPLETE
}
