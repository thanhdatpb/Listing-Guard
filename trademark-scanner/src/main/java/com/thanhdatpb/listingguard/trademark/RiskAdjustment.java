package com.thanhdatpb.listingguard.trademark;

/**
 * One reason a catalog finding is reported lower than the level its rule assigns (ADR-009). The original level is never
 * hidden: {@code from} is always the level the rule assigned before any adjustment, and {@code to} is the level this
 * reason alone gives. When several reasons apply, each one is listed with the same {@code from}, and the finding's own
 * {@code risk} is the lowest {@code to}. An adjustment only exists when it lowers the level.
 *
 * @param reason why the level is lower
 * @param from   the level the rule assigned (LOW, MEDIUM or HIGH)
 * @param to     the level this reason gives, strictly lower than {@code from}
 * @param note   the ruleset's Vietnamese explanation, with the real values filled in
 */
public record RiskAdjustment(AdjustmentReason reason, RiskLevel from, RiskLevel to, String note) {

    public RiskAdjustment {
        if (!from.isDetermined() || !to.isDetermined() || to.ordinal() >= from.ordinal()) {
            throw new IllegalArgumentException("An adjustment must lower a determined level: " + from + " to " + to);
        }
    }
}
