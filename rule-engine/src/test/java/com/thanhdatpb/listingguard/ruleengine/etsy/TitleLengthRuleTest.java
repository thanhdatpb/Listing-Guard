package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleLengthRuleTest {

    private final TitleLengthRule rule = new TitleLengthRule();
    private final int max = limit("title_length", "max_length");

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of()), CONFIG);
    }

    @Test
    void shortTitleIsValid() {
        assertThat(run("Handmade mug")).isEmpty();
    }

    @Test
    void titleOfExactlyMaxLengthIsValid() {
        assertThat(run("x".repeat(max))).isEmpty();
    }

    @Test
    void titleOneOverMaxIsAnError() {
        List<Finding> findings = run("x".repeat(max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_length");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.message()).isEqualTo("Tiêu đề dài " + (max + 1) + " ký tự, tối đa " + max + ".");
        assertThat(finding.suggestion()).contains("1 ký tự");
        assertThat(finding.rulesetVersion()).isEqualTo("etsy-2026-10");
    }

    @Test
    void suggestionSaysHowManyCharactersToCut() {
        assertThat(run("x".repeat(max + 30)).get(0).suggestion()).contains("30 ký tự");
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
}
