package com.thanhdatpb.listingguard.client.model;

import java.util.List;

/**
 * The answer of {@code GET /api/v1/rulesets/{marketplace}/limits} (schema {@code RulesetLimitsResult}): the numbers the checks
 * use, for a character counter. Read them again when {@code rulesetVersion} changes.
 *
 * @param marketplace    the marketplace code
 * @param productType    the product type asked for, or {@code null}
 * @param rulesetVersion the ruleset the numbers come from
 * @param limits         the limits
 */
public record RulesetLimits(String marketplace, String productType, String rulesetVersion, List<Limit> limits) {

    public RulesetLimits {
        limits = Copies.list(limits);
    }
}
