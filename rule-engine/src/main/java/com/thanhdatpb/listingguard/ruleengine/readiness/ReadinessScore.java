package com.thanhdatpb.listingguard.ruleengine.readiness;

import java.util.List;

/**
 * The publish readiness of one listing for one rule run (ADR-014). It covers the findings of one pack only
 * ({@code packId}), never trademark findings.
 *
 * <p>{@code ready} means none of the rules found an ERROR and every rule ran. It does not mean a marketplace will
 * accept the listing. {@code score} is null exactly when the run was {@link Status#INCOMPLETE}; a listing that is not
 * ready never scores above the formula's {@code not_ready_max_score}.
 */
public record ReadinessScore(
        String version,
        String packId,
        Status status,
        Integer score,
        boolean ready,
        int errors,
        int warnings,
        int infos,
        List<String> failedRuleIds) {

    public enum Status {
        /** Every rule ran; {@code score} is set. */
        SCORED,
        /** A rule failed, so no number is given and the listing is not ready. */
        INCOMPLETE
    }

    public ReadinessScore {
        failedRuleIds = List.copyOf(failedRuleIds);
        if (status == Status.SCORED ? score == null || score < 0 || score > 100 : score != null || ready) {
            throw new IllegalArgumentException("Inconsistent readiness score: " + status + " with score " + score
                    + " and ready=" + ready);
        }
    }
}
