package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleRepeatedWordsRuleTest {

    private final TitleRepeatedWordsRule rule = new TitleRepeatedWordsRule();
    private final int max = limit("title_repeated_words", "max_occurrences");
    private final String exempt = CONFIG.rule("title_repeated_words").stringListParam("exempt_words").get(0);

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of("mug")), CONFIG);
    }

    private static String repeated(String word, int times) {
        return String.join(" ", Collections.nCopies(times, word));
    }

    @Test
    void aWordUpToTheMaximumIsValid() {
        assertThat(run(repeated("mug", max))).isEmpty();
    }

    @Test
    void aWordOneOverTheMaximumIsAWarning() {
        List<Finding> findings = run(repeated("mug", max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_repeated_words");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo("mug");
        assertThat(finding.message()).isEqualTo("Từ 'mug' xuất hiện " + (max + 1) + " lần trong tiêu đề, tối đa " + max + " lần.");
        assertThat(finding.suggestion()).isEqualTo("Bỏ bớt 1 lần xuất hiện của từ 'mug' trong tiêu đề.");
        assertThat(finding.fix()).as("which occurrence to drop is the seller's choice").isNull();
    }

    @Test
    void aTitleThatRepeatsAWordAroundOtherWordsIsReported() {
        String title = java.util.stream.IntStream.rangeClosed(0, max).mapToObj(i -> "mug for item" + i)
                .collect(java.util.stream.Collectors.joining(" "));

        assertThat(run(title)).extracting(Finding::value).containsExactly("mug");
    }

    @Test
    void caseIsIgnoredAndTheFirstFormIsReported() {
        assertThat(run("Mug MUG" + " mug".repeat(max))).extracting(Finding::value).containsExactly("Mug");
    }

    @Test
    void exemptWordsAreNotCounted() {
        assertThat(run(repeated(exempt, max + 5))).isEmpty();
        assertThat(run(repeated(exempt.toUpperCase(), max + 5))).isEmpty();
    }

    @Test
    void everyWordOverTheMaximumGetsItsOwnFindingInOrderOfFirstAppearance() {
        String title = repeated("alpha", max + 1) + " " + repeated("beta", max + 2) + " gamma";

        assertThat(run(title)).extracting(Finding::value).containsExactly("alpha", "beta");
    }

    @Test
    void aTitleWithNoRepeatsOrNoTitleHasNoFinding() {
        assertThat(run("Handmade ceramic mug")).isEmpty();
        assertThat(rule.evaluate(listing(null, List.of("mug")), CONFIG)).isEmpty();
    }

    @Test
    void theExemptListAndTheMaximumComeFromTheConfigAndAreValidated() {
        RulesetConfig config = RulesetLoader.parse("""
                marketplace: ETSY
                version: t
                checked_at: 2026-11-06
                rules:
                  title_repeated_words:
                    severity: WARNING
                    max_occurrences: 1
                    exempt_words: [red]
                    source:
                      status: unverified
                """);

        assertThat(rule.evaluate(listing("red red blue blue", List.of()), config)).extracting(Finding::value)
                .containsExactly("blue");

        RulesetConfig broken = RulesetLoader.parse("""
                marketplace: ETSY
                version: t
                checked_at: 2026-11-06
                rules:
                  title_repeated_words:
                    severity: WARNING
                    max_occurrences: 1
                    source:
                      status: unverified
                """);
        assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class)
                .hasMessageContaining("exempt_words");
    }

    @Test
    void theRulesetRatesItAsAWarningWithASecondarySource() {
        assertThat(CONFIG.rule("title_repeated_words").severity()).isEqualTo(Severity.WARNING);
        assertThat(CONFIG.rule("title_repeated_words").source().status()).isEqualTo(SourceStatus.SECONDARY);
    }
}
