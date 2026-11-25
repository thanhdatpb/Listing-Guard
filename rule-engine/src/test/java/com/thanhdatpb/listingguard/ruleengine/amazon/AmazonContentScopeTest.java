package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Amazon presence rule under a Sheet that has no {@code product_type} column (ADR-052). */
class AmazonContentScopeTest {

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    private static Listing titleOnly() {
        return AmazonTestData.listing("A synthetic mug", "Zibblefrax", List.of("A bullet"), "A description.", "mug", null);
    }

    @Test
    void aMissingProductTypeColumnIsNotAnErrorAndIsNamed() {
        RuleRun run = engine.runDetailed(titleOnly(), CONFIG, Set.of("title", "description", "bullet_points"));

        assertThat(run.findings()).extracting(Finding::ruleId).doesNotContain("required_fields");
        assertThat(run.skippedRuleIds()).containsExactly("required_fields");
        assertThat(run.skippedFields()).containsExactly("product_type");
    }

    @Test
    void aProductTypeColumnThatIsEmptyIsStillAnError() {
        RuleRun run = engine.runDetailed(titleOnly(), CONFIG, Set.of("title", "product_type"));

        assertThat(run.findings()).filteredOn(finding -> finding.ruleId().equals("required_fields"))
                .extracting(Finding::field).containsExactly("product_type");
        assertThat(run.partial()).isFalse();
    }

    @Test
    void theFullRunIsUnchanged() {
        assertThat(engine.runDetailed(titleOnly(), CONFIG, null)).isEqualTo(engine.runDetailed(titleOnly(), CONFIG));
        assertThat(engine.runDetailed(titleOnly(), CONFIG).findings()).extracting(Finding::field).contains("product_type");
    }
}
