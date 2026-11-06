package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TitleWordCountRuleTest {

    private final TitleWordCountRule rule = new TitleWordCountRule();
    private final int max = limit("title_word_count", "max_words");

    private static String words(int count) {
        return IntStream.range(0, count).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
    }

    private List<Finding> run(String title) {
        return rule.evaluate(listing(title, List.of("mug")), CONFIG);
    }

    @Test
    void aTitleOfExactlyTheMaximumWordsIsValid() {
        assertThat(run(words(max))).isEmpty();
    }

    @Test
    void oneWordOverTheMaximumIsAnInfoWithTheTitleAsItsValue() {
        String title = words(max + 1);

        List<Finding> findings = run(title);

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("title_word_count");
        assertThat(finding.severity()).isEqualTo(Severity.INFO);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo(title);
        assertThat(finding.message()).isEqualTo("Tiêu đề có " + (max + 1) + " từ, hướng dẫn của Etsy khuyên tối đa " + max + " từ.");
        assertThat(finding.suggestion()).startsWith("Rút gọn tiêu đề ít nhất 1 từ");
        assertThat(finding.fix()).as("no rule proposes the words to drop").isNull();
    }

    @Test
    void theSuggestionSaysHowManyWordsToDrop() {
        assertThat(run(words(max + 4)).get(0).suggestion()).startsWith("Rút gọn tiêu đề ít nhất 4 từ");
    }

    @Test
    void punctuationSeparatesWordsAndAnApostropheDoesNot() {
        assertThat(run(words(max - 1) + " men's")).isEmpty();
        assertThat(run(words(max - 1) + " red,blue")).hasSize(1);
        assertThat(run(words(max - 1) + " - & +")).isEmpty();
    }

    @Test
    void articlesAndPrepositionsCountAsWords() {
        assertThat(run(words(max - 1) + " the")).isEmpty();
        assertThat(run(words(max) + " the")).hasSize(1);
    }

    @Test
    void noTitleHasNoFinding() {
        Listing noTitle = listing(null, List.of("mug"));

        assertThat(rule.evaluate(noTitle, CONFIG)).isEmpty();
    }

    @Test
    void theRulesetRatesItAsInfoWithASecondarySource() {
        assertThat(CONFIG.rule("title_word_count").severity()).isEqualTo(Severity.INFO);
        assertThat(CONFIG.rule("title_word_count").source().status()).isEqualTo(SourceStatus.SECONDARY);
    }
}
