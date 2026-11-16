package com.thanhdatpb.listingguard.api.feedback;

import com.thanhdatpb.listingguard.api.plan.Pack;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The address of one stored finding, as a client already sees it (ADR-034): the pack, the id of the stored result and the
 * finding's index in that result's {@code findings}, in the order {@code GET} returns them. No response needs an id of its own.
 *
 * @param pack     one of {@link #PACKS}
 * @param checkId  the stored result
 * @param position the index of the finding in the result, from 0
 */
public record FeedbackTarget(String pack, UUID checkId, int position) {

    /** The packs whose findings can be answered: they store their findings (V37 {@code ck_finding_verdict_pack}). */
    public static final List<String> PACKS = List.of(Pack.LISTING_RULES.id(), Pack.TRADEMARK_RISK.id(),
            Pack.VARIATION_CHECKS.id());

    public FeedbackTarget {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(checkId, "checkId");
        if (!PACKS.contains(pack)) {
            throw new IllegalArgumentException("Feedback is not taken for this pack");
        }
        if (position < 0) {
            throw new IllegalArgumentException("A finding index is never negative");
        }
    }

    /** The key of a map of verdicts by finding, and the HTML id of the widget ({@code fb-listing-rules-<uuid>-3}). */
    public String key() {
        return pack + "-" + checkId + "-" + position;
    }

    public static FeedbackTarget listingRules(UUID checkId, int position) {
        return new FeedbackTarget(Pack.LISTING_RULES.id(), checkId, position);
    }

    public static FeedbackTarget trademark(UUID checkId, int position) {
        return new FeedbackTarget(Pack.TRADEMARK_RISK.id(), checkId, position);
    }

    public static FeedbackTarget variation(UUID checkId, int position) {
        return new FeedbackTarget(Pack.VARIATION_CHECKS.id(), checkId, position);
    }
}
