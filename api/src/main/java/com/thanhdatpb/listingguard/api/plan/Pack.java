package com.thanhdatpb.listingguard.api.plan;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What a plan can include (ADR-013). {@code listing-rules}, {@code trademark-risk}, {@code policy-analyzer} and
 * {@code variation-checks} and {@code personalization-screening} are analysis packs of ADR-010 and their ids are the
 * {@code pack_id} of the rulesets;
 * {@code batch} and {@code sheet-connector} are capabilities sold the same way. Held equal to the
 * {@code ck_plan_pack_pack_id} constraint (V19, replaced by V22 for ADR-015 and by V24 for ADR-016) by a test.
 *
 * <p>{@link #POLICY_ANALYZER} and {@link #SHEET_CONNECTOR} are reserved: nothing calls {@link PlanService#requirePack} for
 * them yet. The policy analyzer endpoint (ADR-011, PR 5) and the sheet connector (phase 4b) will.
 */
public enum Pack {
    LISTING_RULES("listing-rules"),
    TRADEMARK_RISK("trademark-risk"),
    POLICY_ANALYZER("policy-analyzer"),
    BATCH("batch"),
    SHEET_CONNECTOR("sheet-connector"),
    VARIATION_CHECKS("variation-checks"),
    PERSONALIZATION_SCREENING("personalization-screening"),
    /** The plan pack of the image endpoint (ADR-008). Its ruleset keeps the analysis pack id {@code image-compliance} (ADR-010). */
    IMAGE_CHECKS("image-checks");

    private final String id;

    Pack(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<Pack> fromId(String id) {
        return Arrays.stream(values()).filter(pack -> pack.id.equals(id)).findFirst();
    }

    /** Every pack id, sorted: the packs of a deployment where plans are off. */
    public static List<String> allIds() {
        return Arrays.stream(values()).map(Pack::id).sorted().toList();
    }
}
