package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
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

class TagCharactersRuleTest {

    private final TagCharactersRule rule = new TagCharactersRule();

    private List<Finding> run(String... tags) {
        return rule.evaluate(listing("Mug", List.of(tags)), CONFIG);
    }

    @Test
    void lettersDigitsSpacesHyphenApostropheAndTrademarkSignsAreValid() {
        assertThat(run("kid's room-decor", "Gift 2024", "mug™", "brand©", "name®")).isEmpty();
    }

    @Test
    void nonAsciiLettersAreValid() {
        assertThat(run("đồ chơi", "café")).isEmpty();
    }

    @Test
    void underscoreIsNotAllowed() {
        List<Finding> findings = run("gift_idea");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("tag_characters");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("tags[0]");
        assertThat(finding.message()).isEqualTo("Tag 'gift_idea' chứa ký tự không hợp lệ: '_'.");
    }

    @Test
    void listsEachDistinctInvalidCharacterOnce() {
        Finding finding = run("a/b#c__d").get(0);

        assertThat(finding.message()).contains("'/' '#' '_'");
        assertThat(finding.message()).doesNotContain("'_' '_'");
    }

    @Test
    void emojiAreNotAllowed() {
        assertThat(run("party 😀")).hasSize(1);
    }

    @Test
    void pointsAtTheOffendingTagIndex() {
        assertThat(run("ok", "bad!", "fine")).extracting(Finding::field).containsExactly("tags[1]");
    }

    @Test
    void invalidRegexInTheRulesetFailsValidation() {
        RulesetConfig broken = RulesetLoader.parse("""
                marketplace: ETSY
                version: broken
                checked_at: 2026-10-27
                rules:
                  tag_characters:
                    severity: ERROR
                    allowed_char_class: "[unclosed"
                    source:
                      status: unverified
                """);

        assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class);
    }
}
