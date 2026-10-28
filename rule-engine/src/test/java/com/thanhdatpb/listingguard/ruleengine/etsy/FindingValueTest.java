package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.empty;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Every finding carries the exact value that caused it, so a client can match by value instead of by
 * position; findings without a single offending value have null.
 */
class FindingValueTest {

    private static Finding only(List<Finding> findings) {
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    @Test
    void titleRulesReportTheTitleOrTheRepeatedCharacter() {
        String longTitle = "x".repeat(limit("title_length", "max_length") + 1);
        String emoji = "Mug 😀";

        assertThat(only(new TitleLengthRule().evaluate(listing(longTitle, List.of()), CONFIG)).value()).isEqualTo(longTitle);
        assertThat(only(new TitleCharactersRule().evaluate(listing(emoji, List.of()), CONFIG)).value()).isEqualTo(emoji);
        assertThat(only(new TitleRepeatedSpecialCharsRule().evaluate(listing("50% off 100% fun", List.of()), CONFIG)).value())
                .isEqualTo("%");
    }

    @Test
    void tagRulesReportTheOffendingTagItself() {
        String tooLong = "y".repeat(limit("tag_length", "max_length") + 1);

        assertThat(only(new TagLengthRule().evaluate(listing("Mug", List.of("ok", tooLong)), CONFIG)).value()).isEqualTo(tooLong);
        assertThat(only(new TagCharactersRule().evaluate(listing("Mug", List.of("fine", "bad_tag")), CONFIG)).value())
                .isEqualTo("bad_tag");
        Finding duplicate = only(new DuplicateTagsRule().evaluate(listing("Mug", List.of("Mug", "mug")), CONFIG));
        assertThat(duplicate.field()).isEqualTo("tags[1]");
        assertThat(duplicate.value()).isEqualTo("mug");
    }

    @Test
    void priceAndQuantityReportTheirValues() {
        Listing base = listing("Mug", List.of());
        Listing bad = new Listing(base.title(), base.description(), base.tags(), new Money(new BigDecimal("0"), "USD"),
                base.category(), base.materials(), base.images(), -2, base.whoMade(), base.whenMade());

        List<Finding> findings = new PriceQuantityPositiveRule().evaluate(bad, CONFIG);

        assertThat(findings).extracting(Finding::field).containsExactly("price", "quantity");
        assertThat(findings).extracting(Finding::value).containsExactly("0", "-2");
    }

    @Test
    void bannedWordsReportTheWordAsItAppearsInTheText() {
        String word = CONFIG.rule("banned_words").stringListParam("words").get(0);
        String shouted = word.toUpperCase();

        Finding finding = only(new BannedWordsRule().evaluate(listing("Premium " + shouted + " bag", List.of()), CONFIG));

        assertThat(finding.value()).isEqualTo(shouted);
    }

    @Test
    void findingsWithoutASingleOffendingValueHaveNullValue() {
        List<String> tooMany = IntStream.range(0, limit("tag_count", "max_count") + 1).mapToObj(i -> "tag" + i).toList();

        assertThat(only(new TagCountRule().evaluate(listing("Mug", tooMany), CONFIG)).value()).isNull();
        assertThat(new RequiredFieldsRule().evaluate(empty(), CONFIG)).isNotEmpty()
                .allSatisfy(finding -> assertThat(finding.value()).isNull());
    }
}
