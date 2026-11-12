package com.thanhdatpb.listingguard.client.model;

/**
 * Why a trademark finding's level is lower than the match alone would give (schema {@code RiskAdjustmentView}).
 *
 * @param reason the reason's key
 * @param from   the level before the adjustment
 * @param to     the level after it
 * @param note   the reason in words
 */
public record RiskAdjustment(String reason, RiskLevel from, RiskLevel to, String note) {
}
