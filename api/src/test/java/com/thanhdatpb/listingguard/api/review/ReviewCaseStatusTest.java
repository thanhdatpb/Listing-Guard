package com.thanhdatpb.listingguard.api.review;

import static com.thanhdatpb.listingguard.api.review.ReviewCaseStatus.DISMISSED;
import static com.thanhdatpb.listingguard.api.review.ReviewCaseStatus.IN_REVIEW;
import static com.thanhdatpb.listingguard.api.review.ReviewCaseStatus.OPEN;
import static com.thanhdatpb.listingguard.api.review.ReviewCaseStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReviewCaseStatusTest {
    @Test
    void onlyTheApprovedTransitionsArePossible() {
        var states = Arrays.asList(ReviewCaseStatus.values());
        assertThat(states).filteredOn(OPEN::permits).containsExactly(IN_REVIEW);
        assertThat(states).filteredOn(IN_REVIEW::permits).containsExactlyInAnyOrder(RESOLVED, DISMISSED);
        assertThat(states).filteredOn(RESOLVED::permits).isEmpty();
        assertThat(states).filteredOn(DISMISSED::permits).isEmpty();
    }
}
