package com.thanhdatpb.listingguard.api.trademark.judge;

import java.util.Set;
import java.util.UUID;

/**
 * What the judge needs to know about the check it is part of, to build an evidence pack (ADR-051): whose check it is, for the client's
 * own verified decisions; the marketplace, because a verified case belongs to one; and the Nice classes of the product type the
 * listing declared, from the ruleset's own mapping (ADR-009), or null when it declared none. An evaluation job has no client:
 * {@link #NONE} makes the client's data {@code unknown} and nothing else changes.
 */
public record JudgeContext(UUID clientId, String marketplace, Set<Integer> productClasses) {

    public static final JudgeContext NONE = new JudgeContext(null, null, null);

    public JudgeContext {
        productClasses = productClasses == null ? null : Set.copyOf(productClasses);
    }
}
