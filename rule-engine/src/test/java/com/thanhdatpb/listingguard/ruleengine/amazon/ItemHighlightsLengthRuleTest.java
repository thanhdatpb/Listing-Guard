package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withItemHighlights;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The limit is read from the ruleset; nothing here repeats the number. */
class ItemHighlightsLengthRuleTest {

    private final ItemHighlightsLengthRule rule = new ItemHighlightsLengthRule();
    private final int max = limit("item_highlights_length", "max_length");

    private List<Finding> run(String itemHighlights) {
        return rule.evaluate(withItemHighlights(itemHighlights), CONFIG);
    }

    @Test
    void aListingWithoutItemHighlightsHasNoFinding() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
        assertThat(run(null)).isEmpty();
    }

    @Test
    void itemHighlightsOfExactlyTheLimitAreValid() {
        assertThat(run("x".repeat(max))).isEmpty();
    }

    @Test
    void oneCharacterOverTheLimitIsAWarningWithTheRealNumbers() {
        String text = "x".repeat(max + 1);

        List<Finding> findings = run(text);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("item_highlights_length");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("item_highlights");
        assertThat(finding.value()).isEqualTo(text);
        assertThat(finding.message()).isEqualTo("Item Highlights dài " + (max + 1) + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).isEqualTo("Rút ngắn Item Highlights ít nhất 1 ký tự.");
        assertThat(finding.rulesetVersion()).isEqualTo(CONFIG.version());
    }

    @Test
    void charactersAreCountedAsCodePointsNotUtf16Units() {
        assertThat(run("😀".repeat(max))).isEmpty();
        assertThat(run("😀".repeat(max + 1))).hasSize(1);
    }

    @Test
    void thereIsNoFixEvenWhenFixesAreComputed() {
        List<Finding> findings = new RuleEngine(RuleRegistry.withDefaults())
                .runDetailed(withItemHighlights("word ".repeat(max)), CONFIG).findings();

        assertThat(findings).extracting(Finding::ruleId).containsExactly("item_highlights_length");
        assertThat(findings.get(0).fix()).isNull();
    }
}
