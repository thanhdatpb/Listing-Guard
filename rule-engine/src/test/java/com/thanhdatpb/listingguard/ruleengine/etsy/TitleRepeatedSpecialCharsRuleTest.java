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
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleRepeatedSpecialCharsRuleTest {

    private final TitleRepeatedSpecialCharsRule rule = new TitleRepeatedSpecialCharsRule();
    private final int max = limit("title_repeated_special_chars", "max_occurrences");

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of()), CONFIG);
    }

    @Test
    void eachConfiguredCharacterUpToTheLimitIsValid() {
        assertThat(run("50% off: gift & more")).isEmpty();
    }

    @Test
    void noSpecialCharactersIsValid() {
        assertThat(run("Handmade mug")).isEmpty();
    }

    @Test
    void missingTitleIsLeftToRequiredFields() {
        assertThat(run(null)).isEmpty();
    }

    @Test
    void oneOverTheLimitIsAWarningNotAnError() {
        List<Finding> findings = run("100% cotton 50% off");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_repeated_special_chars");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.message())
                .isEqualTo("Ký tự '%' xuất hiện " + (max + 1) + " lần trong tiêu đề, tối đa " + max + " lần.");
        assertThat(finding.suggestion()).contains("bỏ 1 ký tự còn lại");
    }

    @Test
    void reportsEachOffendingCharacterSeparately() {
        List<Finding> findings = run("a & b & c : d : e : f");

        assertThat(findings).extracting(Finding::message)
                .anyMatch(message -> message.contains("'&'"))
                .anyMatch(message -> message.contains("'" + ":" + "' xuất hiện 3 lần"));
        assertThat(findings).hasSize(2);
    }

    @Test
    void charactersNotInTheRulesetAreNotChecked() {
        assertThat(run("a + b + c + d")).isEmpty();
    }

    @Test
    void multiCharacterEntryInTheRulesetFailsValidation() {
        RulesetConfig broken = RulesetLoader.parse("""
                marketplace: ETSY
                version: broken
                checked_at: 2026-10-27
                rules:
                  title_repeated_special_chars:
                    severity: WARNING
                    chars: ["%%", ":"]
                    max_occurrences: 1
                    source:
                      status: unverified
                """);

        assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class);
    }
}
