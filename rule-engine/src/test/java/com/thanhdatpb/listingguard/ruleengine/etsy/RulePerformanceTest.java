package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Rule layer budget: under 50 ms per listing, measured on a large listing after warm-up. */
class RulePerformanceTest {

    private static final double BUDGET_MS = 50.0;

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    @Test
    void ruleLayerStaysUnderBudgetPerListing() {
        List<String> tags = IntStream.range(0, limit("tag_count", "max_count") + 2)
                .mapToObj(i -> "tag " + (i % 5) + " " + "x".repeat(limit("tag_length", "max_length")))
                .toList();
        Listing base = valid();
        Listing large = new Listing("t".repeat(limit("title_length", "max_length") + 10), "lorem ipsum dolor ".repeat(1000),
                tags, base.price(), base.category(), base.materials(), base.images(), base.quantity(), base.whoMade(),
                base.whenMade());

        for (int i = 0; i < 200; i++) {
            engine.run(large, CONFIG);
        }
        int runs = 500;
        List<Finding> findings = null;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            findings = engine.run(large, CONFIG);
        }
        double averageMs = (System.nanoTime() - start) / 1_000_000.0 / runs;

        assertThat(findings).isNotEmpty();
        assertThat(averageMs).as("average ms per listing").isLessThan(BUDGET_MS);
    }
}
