package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;

/**
 * A {@link Limit} of one rule of a ruleset, with what the ruleset says about that rule for one category: how a breach is
 * rated, how well the number is backed, and whether the rule runs at all.
 *
 * <p>A rule that is switched off ({@code enabled: false}) is still listed, with {@code enabled} false: its number stays on
 * record, and a client decides whether to show it. Nothing is enforced by a limit that is not enabled.
 *
 * @param field  the field of {@code docs/input-schema.md} the limit applies to
 * @param ruleId the rule that enforces it
 */
public record RuleLimit(String field, String ruleId, Limit.Kind kind, int value, Limit.Unit unit, Severity severity,
                        SourceStatus sourceStatus, boolean enabled) {
}
