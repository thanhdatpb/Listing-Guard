package com.thanhdatpb.listingguard.api.feedback;

/**
 * Where a finding came from (ADR-034). Keep the list equal to the {@code CHECK} constraint {@code ck_finding_verdict_source}
 * of V37 (a test holds it).
 */
public enum FeedbackSource {
    /** A rule of a ruleset: the listing rules, the variation rules, and the trademark context rules that match no mark. */
    RULE,
    /** A mark of the served trademark catalog. */
    CATALOG,
    /** Reserved for the watchlist pack, which is not on main yet: nothing produces it today. */
    WATCHLIST
}
