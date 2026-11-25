package com.thanhdatpb.listingguard.client.model;

/**
 * Why a trademark finding's level is lower than the match alone would give (schema {@code RiskAdjustmentView}).
 *
 * @param reason the reason's key
 * @param from   the level before the adjustment
 * @param to     the level after it
 * @param note   the reason in words
 * @param distinctiveness unverified catalog signals for a tier-based adjustment, or {@code null}
 * @param judgeEvidence   what the LLM judge was shown and its reason, only on {@code llm_descriptive} when the operator chose the
 *                        evidence prompt, or {@code null}
 */
public record RiskAdjustment(String reason, RiskLevel from, RiskLevel to, String note,
                             TrademarkCalibrationEvidence distinctiveness, TrademarkJudgeEvidence judgeEvidence) {

    public RiskAdjustment(String reason, RiskLevel from, RiskLevel to, String note) {
        this(reason, from, to, note, null, null);
    }

    public RiskAdjustment(String reason, RiskLevel from, RiskLevel to, String note, TrademarkCalibrationEvidence distinctiveness) {
        this(reason, from, to, note, distinctiveness, null);
    }
}
