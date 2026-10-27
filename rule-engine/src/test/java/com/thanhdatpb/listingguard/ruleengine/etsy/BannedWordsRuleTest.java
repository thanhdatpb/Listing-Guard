package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.withDescription;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class BannedWordsRuleTest {

    private final BannedWordsRule rule = new BannedWordsRule();
    private final String word = CONFIG.rule("banned_words").stringListParam("words").get(0);

    @Test
    void cleanListingIsValid() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
    }

    @Test
    void wordInTheTitleIsAWarning() {
        List<Finding> findings = rule.evaluate(listing("Premium " + word + " bag", List.of()), CONFIG);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("banned_words");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.message()).contains("'" + word + "'").contains("title");
    }

    @Test
    void wordInTheDescriptionIsFlagged() {
        List<Finding> findings = rule.evaluate(withDescription("This is a " + word + "."), CONFIG);

        assertThat(findings).extracting(Finding::field).containsExactly("description");
    }

    @Test
    void wordInATagIsFlaggedWithItsIndex() {
        List<Finding> findings = rule.evaluate(listing("Mug", List.of("ok", word)), CONFIG);

        assertThat(findings).extracting(Finding::field).containsExactly("tags[1]");
    }

    @Test
    void matchingIgnoresCase() {
        assertThat(rule.evaluate(listing(word.toUpperCase() + " mug", List.of()), CONFIG)).hasSize(1);
    }

    @Test
    void onlyWholeWordsMatch() {
        assertThat(rule.evaluate(listing("Mug " + word + "x", List.of()), CONFIG)).isEmpty();
        assertThat(rule.evaluate(listing("Mug non" + word, List.of()), CONFIG)).isEmpty();
    }

    @Test
    void oneFindingPerFieldEvenWhenTheWordRepeats() {
        assertThat(rule.evaluate(listing(word + " " + word, List.of()), CONFIG)).hasSize(1);
    }

    @Test
    void reportsEveryFieldContainingTheWord() {
        assertThat(rule.evaluate(listing(word + " mug", List.of(word)), CONFIG)).extracting(Finding::field)
                .containsExactly("title", "tags[0]");
    }
}
