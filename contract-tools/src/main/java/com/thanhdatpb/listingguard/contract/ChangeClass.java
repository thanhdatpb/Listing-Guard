package com.thanhdatpb.listingguard.contract;

/** How a change of the contract affects a client that was built against the baseline (ADR-022, sections 2 and 3). */
public enum ChangeClass {

    /** A client of the baseline may stop working. Not allowed inside {@code /api/v1} unless declared (ADR-022, section 5). */
    BREAKING,

    /** Something new that a tolerant reader ignores safely. Listed for the release notes. */
    ADDITIVE,

    /** Neither new nor breaking: a relaxation, a narrowing, a deprecation, a renamed schema. Listed too. */
    NON_BREAKING;

    /** The word the ADR table uses: {@code breaking}, {@code additive}, {@code non-breaking}. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
