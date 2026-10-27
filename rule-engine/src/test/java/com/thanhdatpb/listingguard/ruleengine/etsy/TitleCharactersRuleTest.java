package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleCharactersRuleTest {

    private final TitleCharactersRule rule = new TitleCharactersRule();

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of()), CONFIG);
    }

    @Test
    void lettersNumbersPunctuationAndSymbolsAreValid() {
        assertThat(run("Handmade Mug - 100% cotton & linen: gift, set (2) + more! #1_best")).isEmpty();
    }

    @Test
    void nonAsciiLettersAndTrademarkSignsAreValid() {
        assertThat(run("Đồ chơi gỗ café™ © ®")).isEmpty();
    }

    @Test
    void mathematicalSymbolsAreValid() {
        assertThat(run("a < b = c > d")).isEmpty();
    }

    @Test
    void missingTitleIsLeftToRequiredFields() {
        assertThat(run(null)).isEmpty();
    }

    @Test
    void emojiIsAnError() {
        List<Finding> findings = run("Mug 😀");

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_characters");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.message()).isEqualTo("Tiêu đề chứa ký tự không hợp lệ: '😀'.");
    }

    @Test
    void currencySymbolsAndModifierSymbolsAreNotAllowed() {
        assertThat(run("Price $5").get(0).message()).contains("'$'");
        assertThat(run("a^b").get(0).message()).contains("'^'");
    }

    @Test
    void controlCharactersAreNotAllowed() {
        assertThat(run("Mug\tset")).hasSize(1);
    }

    @Test
    void listsEachDistinctInvalidCharacterOnce() {
        String message = run("$a$ ^b😀").get(0).message();

        assertThat(message).contains("'$' '^' '😀'");
        assertThat(message).doesNotContain("'$' '$'");
    }
}
