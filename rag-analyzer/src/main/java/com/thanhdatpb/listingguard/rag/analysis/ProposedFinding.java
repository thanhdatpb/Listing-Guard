package com.thanhdatpb.listingguard.rag.analysis;

import com.thanhdatpb.listingguard.core.finding.Severity;

/**
 * A finding as the model proposed it: the right shape, but its citation is not checked yet. The severity may
 * still be {@link Severity#ERROR}; {@link CitationVerifier} clamps it.
 */
public record ProposedFinding(
        String ruleId,
        Severity severity,
        String field,
        String value,
        String message,
        String suggestion,
        long chunkId,
        String quote) {
}
