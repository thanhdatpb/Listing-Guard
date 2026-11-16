package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;

/** Where a trademark finding comes from (ADR-031). Decided by the rule id alone, so a stored finding has it too. */
public enum FindingSource {
    /** A mark of the served catalog (the catalog rules). */
    CATALOG,
    /** A context rule of the ruleset: a place with a sport term, or a pure color description. */
    CONTEXT,
    /** An entry of the client's own watchlist. */
    WATCHLIST;

    public static FindingSource of(String ruleId) {
        if (RuleIds.WATCHLIST_MATCH.equals(ruleId)) {
            return WATCHLIST;
        }
        return RuleIds.CATALOG.contains(ruleId) ? CATALOG : CONTEXT;
    }
}
