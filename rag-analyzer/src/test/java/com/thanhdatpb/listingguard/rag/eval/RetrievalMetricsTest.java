package com.thanhdatpb.listingguard.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    private static OptionalInt rank(int r) {
        return OptionalInt.of(r);
    }

    private static final OptionalInt MISS = OptionalInt.empty();

    @Test
    void recallCountsQueriesWhoseFirstHitIsWithinK() {
        List<OptionalInt> ranks = List.of(rank(1), rank(5), rank(6), MISS);

        assertThat(RetrievalMetrics.recallAtK(ranks, 5)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(ranks, 1)).isEqualTo(0.25);
        assertThat(RetrievalMetrics.recallAtK(ranks, 10)).isEqualTo(0.75);
    }

    @Test
    void mrrIsTheMeanOfReciprocalRanksWithMissesCountingZero() {
        List<OptionalInt> ranks = List.of(rank(1), rank(2), rank(4), MISS);

        assertThat(RetrievalMetrics.meanReciprocalRank(ranks)).isCloseTo((1.0 + 0.5 + 0.25 + 0.0) / 4, offset(1e-12));
    }

    @Test
    void perfectAndZeroRetrieval() {
        assertThat(RetrievalMetrics.recallAtK(List.of(rank(1), rank(1)), 5)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.meanReciprocalRank(List.of(rank(1), rank(1)))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(List.of(MISS, MISS), 5)).isZero();
        assertThat(RetrievalMetrics.meanReciprocalRank(List.of(MISS, MISS))).isZero();
    }

    @Test
    void noQueriesIsRejectedInsteadOfInventingANumber() {
        assertThatThrownBy(() -> RetrievalMetrics.recallAtK(List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetrievalMetrics.meanReciprocalRank(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
