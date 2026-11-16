package com.thanhdatpb.listingguard.api.rewrite;

import java.util.List;
import java.util.UUID;

/**
 * What one press of "Gợi ý sửa" produced (ADR-037). {@code candidates} is non-empty only for {@link Status#SUGGESTIONS}; a candidate
 * that did not pass the verification is never in it. {@code runId} names the stored run (null when none was stored) and is what
 * "copied" is recorded against.
 *
 * @param originalTitle the title the suggestions were made for, shown beside each proposal
 */
public record RewriteOutcome(Status status, List<RewriteCandidate> candidates, UUID runId, String originalTitle) {

    public enum Status {
        /** at least one candidate passed every check */
        SUGGESTIONS,
        /** the model answered and no candidate passed: "Không có gợi ý an toàn" */
        NONE_SAFE,
        /** the listing has no ERROR finding and no trademark finding, so there is nothing to rewrite */
        NOT_NEEDED,
        /** the model or a check could not run, or the answer was unusable: nothing is shown */
        UNAVAILABLE,
        /** the client used up its runs of this hour */
        RATE_LIMITED
    }

    public RewriteOutcome {
        candidates = List.copyOf(candidates);
        originalTitle = originalTitle == null ? "" : originalTitle;
    }

    static RewriteOutcome of(Status status, String originalTitle) {
        return new RewriteOutcome(status, List.of(), null, originalTitle);
    }

    public boolean hasSuggestions() {
        return status == Status.SUGGESTIONS;
    }
}
