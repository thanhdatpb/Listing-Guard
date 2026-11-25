package com.thanhdatpb.listingguard.api.rewrite;

import java.util.List;
import java.util.UUID;

/**
 * What one press of "Gợi ý sửa" produced (ADR-037). {@code candidates} is non-empty only for {@link Status#SUGGESTIONS}; a candidate
 * that did not pass the verification is never in it. {@code runId} names the stored run (null when none was stored) and is what
 * "copied" is recorded against.
 *
 * <p>With {@code max-rounds} above 1 (ADR-050) {@code rounds} says how many times the model was asked, {@code maxRounds} how many it
 * could have been, and {@code stop} why the press ended. They only describe the press: no round can show a candidate that did not pass
 * the checks. With {@code max-rounds} 1 the console shows none of them.
 *
 * @param originalTitle the title the suggestions were made for, shown beside each proposal
 * @param rounds        model calls made for this press; 0 when the press ended before the model was asked
 * @param maxRounds     the configured most
 * @param stop          why the rounds ended; null when the model was not asked
 */
public record RewriteOutcome(Status status, List<RewriteCandidate> candidates, UUID runId, String originalTitle, int rounds,
                             int maxRounds, Stop stop) {

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

    /** Why the rounds of a press ended (ADR-050). */
    public enum Stop {
        /** a round had a candidate that passed every check */
        FOUND,
        /** every allowed round was asked and none had a candidate that passed */
        ROUNDS_EXHAUSTED,
        /** a candidate's check could not complete, so asking again would not help */
        CHECK_INCOMPLETE,
        /** the model could not be asked or its answer was unreadable, or a pack or catalog was not available */
        UNAVAILABLE
    }

    public RewriteOutcome {
        candidates = List.copyOf(candidates);
        originalTitle = originalTitle == null ? "" : originalTitle;
    }

    /** An outcome that says nothing about rounds. */
    public RewriteOutcome(Status status, List<RewriteCandidate> candidates, UUID runId, String originalTitle) {
        this(status, candidates, runId, originalTitle, 0, 1, null);
    }

    static RewriteOutcome of(Status status, String originalTitle) {
        return new RewriteOutcome(status, List.of(), null, originalTitle);
    }

    /** The press ended after {@code rounds} calls of the model, for the reason {@code stop}. */
    static RewriteOutcome ended(Status status, List<RewriteCandidate> candidates, UUID runId, String originalTitle, int rounds,
                                int maxRounds, Stop stop) {
        return new RewriteOutcome(status, candidates, runId, originalTitle, rounds, maxRounds, stop);
    }

    public boolean hasSuggestions() {
        return status == Status.SUGGESTIONS;
    }

    /** Whether the panel says how many rounds were tried: only when more than one was allowed and the model was asked. */
    public boolean showRounds() {
        return maxRounds > 1 && rounds > 0 && stop != null;
    }
}
