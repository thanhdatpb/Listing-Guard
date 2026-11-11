package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withDescription;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The limit is read from the ruleset; nothing here repeats the number. */
class DescriptionLengthRuleTest {

    private final DescriptionLengthRule rule = new DescriptionLengthRule();
    private final int max = limit("description_length", "max_length");

    private List<Finding> run(String description) {
        return rule.evaluate(withDescription(description), CONFIG);
    }

    @Test
    void aListingWithoutADescriptionHasNoFinding() {
        assertThat(run(null)).isEmpty();
    }

    @Test
    void aShortDescriptionIsValid() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
    }

    @Test
    void aDescriptionOfExactlyTheLimitIsValid() {
        assertThat(run("x".repeat(max))).isEmpty();
    }

    @Test
    void oneCharacterOverTheLimitIsAWarningWithTheRealNumbers() {
        String text = "x".repeat(max + 1);

        List<Finding> findings = run(text);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("description_length");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("description");
        assertThat(finding.value()).isEqualTo(text);
        assertThat(finding.message()).isEqualTo("Mô tả dài " + (max + 1) + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).isEqualTo("Rút ngắn mô tả ít nhất 1 ký tự.");
        assertThat(finding.rulesetVersion()).isEqualTo(CONFIG.version());
    }

    @Test
    void charactersAreCountedAsCodePointsNotUtf16Units() {
        assertThat(run("😀".repeat(max))).isEmpty();
        assertThat(run("😀".repeat(max + 1))).hasSize(1);
    }

    @Test
    void theRuleIsNeverAnErrorBecauseItsNumberIsUnverified() {
        assertThat(CONFIG.rule("description_length").source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(CONFIG.rule("description_length").severity()).isNotEqualTo(Severity.ERROR);
        assertThat(run("x".repeat(max * 3))).extracting(Finding::severity).containsOnly(Severity.WARNING);
    }

    @Test
    void theRuleDeclaresItsLimitInCodePointsAndTheEngineListsIt() {
        assertThat(rule.limits(CONFIG.rule("description_length")))
                .containsExactly(Limit.maxLength("description", max));

        RuleLimit listed = new RuleEngine(RuleRegistry.withDefaults()).limits(CONFIG, null).stream()
                .filter(limit -> limit.ruleId().equals("description_length")).findFirst().orElseThrow();
        assertThat(listed).isEqualTo(new RuleLimit("description", "description_length", Limit.Kind.MAX_LENGTH, max,
                Limit.Unit.CODE_POINTS, Severity.WARNING, SourceStatus.UNVERIFIED, true));
    }

    @Test
    void thereIsNoFixEvenWhenFixesAreComputed() {
        List<Finding> findings = new RuleEngine(RuleRegistry.withDefaults())
                .runDetailed(withDescription("word ".repeat(max)), CONFIG).findings();

        assertThat(findings).extracting(Finding::ruleId).containsExactly("description_length");
        assertThat(findings.get(0).fix()).isNull();
    }
}
