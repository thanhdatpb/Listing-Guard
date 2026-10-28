package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withTitle;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleLengthRuleTest {

    private final TitleLengthRule rule = new TitleLengthRule();
    private final int max = limit("title_length", "max_length");

    private List<Finding> run(String title) {
        return rule.evaluate(withTitle(title), CONFIG);
    }

    @Test
    void shortTitleIsValid() {
        assertThat(run("Hardside luggage")).isEmpty();
    }

    @Test
    void titleOfExactlyMaxLengthIsValid() {
        assertThat(run("x".repeat(max))).isEmpty();
    }

    @Test
    void titleOneOverMaxIsReportedWithTheConfiguredSeverity() {
        List<Finding> findings = run("x".repeat(max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_length");
        assertThat(finding.severity()).isEqualTo(CONFIG.rule("title_length").severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo("x".repeat(max + 1));
        assertThat(finding.message()).isEqualTo("Tiêu đề dài " + (max + 1) + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).contains("1 ký tự");
        assertThat(finding.rulesetVersion()).isEqualTo(CONFIG.version());
    }

    @Test
    void suggestionSaysHowManyCharactersToCut() {
        assertThat(run("x".repeat(max + 30)).get(0).suggestion()).contains("30 ký tự");
    }

    @Test
    void spacesCountTowardsTheLimit() {
        assertThat(run(" ".repeat(max) + "x")).hasSize(1);
        assertThat(run("ab ".repeat(max / 3))).isEmpty();
    }

    @Test
    void missingTitleIsLeftToRequiredFields() {
        assertThat(run(null)).isEmpty();
    }

    @Test
    void supplementaryCharactersCountOnce() {
        String emoji = "😀";

        assertThat(run(emoji.repeat(max))).isEmpty();
        assertThat(run(emoji.repeat(max + 1))).hasSize(1);
    }

    @Test
    void accentedCharactersCountAsOneCharacterEach() {
        assertThat(run("é".repeat(max))).isEmpty();
    }
}
