package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withBrand;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withTitle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitleForbiddenCharsRuleTest {

    private final TitleForbiddenCharsRule rule = new TitleForbiddenCharsRule();
    private final List<String> forbidden = CONFIG.rule("title_forbidden_chars").stringListParam("chars");

    @Test
    void aTitleWithoutForbiddenCharactersIsValid() {
        assertThat(rule.evaluate(withTitle("Hardside luggage, 20-inch (blue) & black"), CONFIG)).isEmpty();
    }

    @Test
    void everyConfiguredCharacterIsReported() {
        assertThat(forbidden).isNotEmpty();
        for (String character : forbidden) {
            List<Finding> findings = rule.evaluate(withTitle("Luggage " + character + " set"), CONFIG);

            assertThat(findings).as(character).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("title_forbidden_chars");
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("title");
            assertThat(finding.value()).isEqualTo(character);
            assertThat(finding.message()).isEqualTo("Tiêu đề chứa ký tự không được phép: '" + character + "'.");
            assertThat(finding.suggestion()).contains("'" + character + "'").contains("brand");
        }
    }

    @Test
    void aRepeatedCharacterIsReportedOnce() {
        assertThat(rule.evaluate(withTitle("Luggage!!! sale!"), CONFIG)).extracting(Finding::value).containsExactly("!");
    }

    @Test
    void differentCharactersAreReportedInOrderOfAppearance() {
        assertThat(rule.evaluate(withTitle("Luggage ? now $5 !"), CONFIG)).extracting(Finding::value).containsExactly("?", "$", "!");
    }

    @Test
    void aCharacterThatIsPartOfTheBrandIsAllowed() {
        assertThat(rule.evaluate(withBrand("Yahoo! luggage set", "Yahoo!"), CONFIG)).isEmpty();
    }

    @Test
    void aBrandOnlyExcusesTheCharactersItContains() {
        List<Finding> findings = rule.evaluate(withBrand("Yahoo! luggage $ set", "Yahoo!"), CONFIG);

        assertThat(findings).extracting(Finding::value).containsExactly("$");
    }

    @Test
    void withoutABrandNothingIsExcused() {
        assertThat(rule.evaluate(withBrand("Yahoo! luggage", null), CONFIG)).hasSize(1);
    }

    @Test
    void anEmptyOrMissingTitleHasNoFindings() {
        assertThat(rule.evaluate(withTitle(null), CONFIG)).isEmpty();
        assertThat(rule.evaluate(withTitle(""), CONFIG)).isEmpty();
    }

    @Test
    void theListComesFromTheConfig() {
        RulesetConfig config = config("[\"@\"]");

        assertThat(rule.evaluate(withTitle("a@b !"), config)).extracting(Finding::value).containsExactly("@");
    }

    @Test
    void anEntryThatIsNotOneCharacterFailsValidation() {
        assertThatThrownBy(() -> rule.validate(config("[\"!!\"]"))).isInstanceOf(RulesetException.class).hasMessageContaining("!!");
        assertThatThrownBy(() -> rule.validate(config("[\"\"]"))).isInstanceOf(RulesetException.class);
    }

    @Test
    void aSupplementaryCharacterCountsAsOne() {
        assertThat(rule.evaluate(withTitle("Luggage 😀"), config("[\"😀\"]"))).hasSize(1);
        rule.validate(config("[\"😀\"]"));
    }

    private static RulesetConfig config(String chars) {
        return RulesetLoader.parse("""
                marketplace: AMAZON
                version: t
                checked_at: 2026-10-28
                rules:
                  title_forbidden_chars:
                    severity: WARNING
                    chars: %s
                    source:
                      status: unverified
                """.formatted(chars));
    }
}
