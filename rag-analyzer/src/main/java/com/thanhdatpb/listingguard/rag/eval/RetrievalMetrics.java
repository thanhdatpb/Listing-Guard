package com.thanhdatpb.listingguard.rag.eval;

import java.util.List;
import java.util.OptionalInt;

/**
 * Retrieval quality numbers. Each query contributes the rank (1 = best) of its first relevant result, or an
 * empty value when no relevant result was returned.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** Share of queries whose first relevant result is within the top {@code k}. */
    public static double recallAtK(List<OptionalInt> firstHitRanks, int k) {
        requireNotEmpty(firstHitRanks);
        long hits = firstHitRanks.stream().filter(rank -> rank.isPresent() && rank.getAsInt() <= k).count();
        return (double) hits / firstHitRanks.size();
    }

    /** Mean of 1/rank of the first relevant result; a query with no relevant result counts as 0. */
    public static double meanReciprocalRank(List<OptionalInt> firstHitRanks) {
        requireNotEmpty(firstHitRanks);
        return firstHitRanks.stream().mapToDouble(rank -> rank.isPresent() ? 1.0 / rank.getAsInt() : 0.0).sum()
                / firstHitRanks.size();
    }

    private static void requireNotEmpty(List<OptionalInt> ranks) {
        if (ranks.isEmpty()) {
            throw new IllegalArgumentException("No queries: a metric over nothing would be a made-up number");
        }
    }
}
