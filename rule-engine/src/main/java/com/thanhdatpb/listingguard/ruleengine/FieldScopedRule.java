package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;
import java.util.Set;

/**
 * A rule that looks at whether named fields are present, and so can be told that the data it is given never had some of them
 * (ADR-052). A listing read from a Sheet that has no {@code category} column does not lack a category: the column was never part of the
 * table. {@link RuleEngine#runDetailed(Listing, RulesetConfig, Set)} asks such a rule which fields it can leave out, gives it the ones
 * the caller did not supply, and records what was left out so the result is never read as a complete check.
 *
 * <p>Only presence is left out. A field that was supplied and is empty is still reported, and every other rule is untouched.
 */
public interface FieldScopedRule extends Rule {

    /** The fields this rule checks for presence on this listing (the ruleset's list, or the product type's override), in ruleset order. */
    List<String> scopedFields(Listing listing, RulesetConfig config);

    /** As {@link #evaluate(Listing, RulesetConfig)}, but a field in {@code skipped} is not checked and produces no finding. */
    List<Finding> evaluate(Listing listing, RulesetConfig config, Set<String> skipped);
}
