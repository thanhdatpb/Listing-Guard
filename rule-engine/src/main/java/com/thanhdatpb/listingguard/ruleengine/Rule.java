package com.thanhdatpb.listingguard.ruleengine;

/**
 * Placeholder for a deterministic listing rule. The evaluation contract is defined together
 * with the first real rules; thresholds and word lists come from YAML (see ADR-001), never
 * from code.
 */
public interface Rule {

    /** Stable identifier of the rule, e.g. used in findings and feedback. */
    String id();
}
