package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
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

class TitleRepeatedWordsRuleTest {

    private final TitleRepeatedWordsRule rule = new TitleRepeatedWordsRule();
    private final int max = limit("title_repeated_words", "max_occurrences");
    private final String exempt = CONFIG.rule("title_repeated_words").stringListParam("exempt_words").get(0);

    private List<Finding> run(String title) {
        return rule.evaluate(withTitle(title), CONFIG);
    }

    private String repeated(String word, int times) {
        return String.join(" ", java.util.Collections.nCopies(times, word));
    }

    @Test
    void aWordUpToTheMaximumIsValid() {
        assertThat(run(repeated("luggage", max))).isEmpty();
    }

    @Test
    void aWordOneOverTheMaximumIsReported() {
        List<Finding> findings = run(repeated("luggage", max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_repeated_words");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo("luggage");
        assertThat(finding.message()).isEqualTo("Từ 'luggage' xuất hiện " + (max + 1) + " lần trong tiêu đề, tối đa " + max + " lần.");
        assertThat(finding.suggestion()).isEqualTo("Bỏ bớt 1 lần xuất hiện của từ 'luggage' trong tiêu đề.");
    }

    @Test
    void suggestionSaysHowManyTimesToRemove() {
        assertThat(run(repeated("bag", max + 3)).get(0).suggestion()).contains("Bỏ bớt 3 lần");
    }

    @Test
    void caseIsIgnoredAndTheFirstFormIsReported() {
        List<Finding> findings = run("Luggage luggage LUGGAGE" + " luggage".repeat(max - 2));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).value()).isEqualTo("Luggage");
    }

    @Test
    void punctuationSeparatesWords() {
        assertThat(run(String.join(",", java.util.Collections.nCopies(max + 1, "bag")))).hasSize(1);
        assertThat(run(String.join(";", java.util.Collections.nCopies(max, "bag")))).isEmpty();
    }

    @Test
    void anApostropheInsideAWordKeepsItTogether() {
        List<Finding> over = run(repeated("men's", max + 1));

        assertThat(over).extracting(Finding::value).containsExactly("men's");
        assertThat(run(repeated("men's", max))).isEmpty();
        assertThat(run(repeated("men’s", max + 1))).extracting(Finding::value).containsExactly("men’s");
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
    void digitsAndNonAsciiLettersAreWords() {
        assertThat(run(repeated("2024", max + 1))).hasSize(1);
        assertThat(run(repeated("vali", max + 1) + " " + repeated("túi", max + 1))).hasSize(2);
    }

    @Test
    void aTitleWithNoRepeatsOrNoTitleHasNoFindings() {
        assertThat(run("Hardside carry on luggage")).isEmpty();
        assertThat(run(null)).isEmpty();
        assertThat(run("")).isEmpty();
    }

    @Test
    void theExemptListComesFromTheConfigAndIsValidated() {
        RulesetConfig config = RulesetLoader.parse("""
                marketplace: AMAZON
                version: t
                checked_at: 2026-10-28
                rules:
                  title_repeated_words:
                    severity: WARNING
                    max_occurrences: 1
                    exempt_words: [red]
                    source:
                      status: unverified
                """);

        assertThat(rule.evaluate(withTitle("red red blue blue"), config)).extracting(Finding::value).containsExactly("blue");

        RulesetConfig broken = RulesetLoader.parse("""
                marketplace: AMAZON
                version: t
                checked_at: 2026-10-28
                rules:
                  title_repeated_words:
                    severity: WARNING
                    max_occurrences: 1
                    source:
                      status: unverified
                """);
        assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class).hasMessageContaining("exempt_words");
    }
}
