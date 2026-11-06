package com.thanhdatpb.listingguard.variation.rules;

import com.thanhdatpb.listingguard.variation.VariationFinding;
import java.util.List;
import java.util.Set;

/**
 * One rule of the pack (ADR-015). A rule reads the prepared {@link RuleContext}, whose ruleset holds every list, number
 * and message it needs, and returns its findings in a stable order. It throws only when it cannot run; the checker then
 * reports it as failed and keeps the other rules' findings.
 */
public interface VariationRule {

    /** The rule id, the {@code ruleId} of its findings. */
    String id();

    /** The marketplaces the ruleset declares this rule for. */
    Set<String> marketplaces();

    List<VariationFinding> apply(RuleContext context);
}
