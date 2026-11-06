package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The limits the Etsy rules declare are the numbers of the ruleset, and each is counted in the unit the rule declares: a value
 * exactly at the limit passes the rule, one more fails it. Numbers are read from the ruleset, never repeated here.
 */
class EtsyLimitsTest {

    /** One code point, two UTF-16 units: counted by characters (code points) or by Java's length it gives different answers. */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());
    private final List<RuleLimit> limits = engine.limits(CONFIG, null);

    private RuleLimit limitOf(String ruleId) {
        List<RuleLimit> found = limits.stream().filter(l -> l.ruleId().equals(ruleId)).toList();
        assertThat(found).as("limits of " + ruleId).hasSize(1);
        return found.get(0);
    }

    private List<String> ruleIds(Listing listing) {
        return engine.run(listing, CONFIG).stream().map(Finding::ruleId).toList();
    }

    private RuleLimit expected(String ruleId, String field, Limit.Kind kind, String key, Limit.Unit unit) {
        return new RuleLimit(field, ruleId, kind, limit(ruleId, key), unit, CONFIG.rule(ruleId).severity(),
                CONFIG.rule(ruleId).source().status(), CONFIG.rule(ruleId).enabled());
    }

    @Test
    void theLimitsAreTheNumbersOfTheRulesetWithItsSeverityAndSourceStatus() {
        assertThat(limitOf("title_length"))
                .isEqualTo(expected("title_length", "title", Limit.Kind.MAX_LENGTH, "max_length", Limit.Unit.CODE_POINTS));
        assertThat(limitOf("tag_count"))
                .isEqualTo(expected("tag_count", "tags", Limit.Kind.MAX_COUNT, "max_count", Limit.Unit.ITEMS));
        assertThat(limitOf("tag_length"))
                .isEqualTo(expected("tag_length", "tags", Limit.Kind.MAX_LENGTH, "max_length", Limit.Unit.CODE_POINTS));
        assertThat(limitOf("title_repeated_special_chars")).isEqualTo(expected("title_repeated_special_chars", "title",
                Limit.Kind.MAX_OCCURRENCES, "max_occurrences", Limit.Unit.OCCURRENCES));
        assertThat(limitOf("title_word_count"))
                .isEqualTo(expected("title_word_count", "title", Limit.Kind.MAX_COUNT, "max_words", Limit.Unit.WORDS));
        assertThat(limitOf("title_repeated_words")).isEqualTo(expected("title_repeated_words", "title",
                Limit.Kind.MAX_OCCURRENCES, "max_occurrences", Limit.Unit.OCCURRENCES));
    }

    @Test
    void theLimitsFollowTheOrderOfTheRulesAndAnEtsyProductTypeChangesNothing() {
        assertThat(limits).extracting(RuleLimit::ruleId).containsSubsequence("title_length", "tag_count", "tag_length");
        assertThat(engine.limits(CONFIG, "LUGGAGE")).isEqualTo(limits);
    }

    @Test
    void aRuleWithNoNumberDeclaresNoLimit() {
        assertThat(limits).extracting(RuleLimit::ruleId)
                .doesNotContain("required_fields", "title_characters", "tag_characters", "banned_words", "duplicate_tags",
                        "price_quantity_positive", "title_promotional_terms");
    }

    @Test
    void theTitleIsCountedInCodePointsNotInUtf16Units() {
        RuleLimit title = limitOf("title_length");
        String atLimit = EMOJI.repeat(title.value());

        assertThat(title.unit()).isEqualTo(Limit.Unit.CODE_POINTS);
        assertThat(atLimit.length()).as("more UTF-16 units than the limit").isGreaterThan(title.value());
        assertThat(ruleIds(EtsyTestData.listing(atLimit, List.of("mug")))).doesNotContain("title_length");
        assertThat(ruleIds(EtsyTestData.listing(atLimit + "a", List.of("mug")))).contains("title_length");
    }

    @Test
    void eachTagIsCountedInCodePointsAndTheTagsAreCountedAsItems() {
        RuleLimit each = limitOf("tag_length");
        RuleLimit count = limitOf("tag_count");
        String atLimit = EMOJI.repeat(each.value());

        assertThat(each.unit()).isEqualTo(Limit.Unit.CODE_POINTS);
        assertThat(ruleIds(EtsyTestData.listing("Mug", List.of(atLimit)))).doesNotContain("tag_length");
        assertThat(ruleIds(EtsyTestData.listing("Mug", List.of(atLimit + "a")))).contains("tag_length");

        assertThat(count.unit()).isEqualTo(Limit.Unit.ITEMS);
        assertThat(ruleIds(EtsyTestData.listing("Mug", tags(count.value())))).doesNotContain("tag_count");
        assertThat(ruleIds(EtsyTestData.listing("Mug", tags(count.value() + 1)))).contains("tag_count");
    }

    @Test
    void aSpecialCharacterMayAppearAsOftenAsTheLimitSays() {
        RuleLimit occurrences = limitOf("title_repeated_special_chars");
        String character = CONFIG.rule("title_repeated_special_chars").stringListParam("chars").get(0);
        String atLimit = ("a" + character).repeat(occurrences.value());

        assertThat(occurrences.unit()).isEqualTo(Limit.Unit.OCCURRENCES);
        assertThat(ruleIds(EtsyTestData.listing(atLimit, List.of("mug")))).doesNotContain("title_repeated_special_chars");
        assertThat(ruleIds(EtsyTestData.listing(atLimit + character, List.of("mug")))).contains("title_repeated_special_chars");
    }

    @Test
    void theTitleIsCountedInWordsAndTheSameWordInOccurrences() {
        RuleLimit words = limitOf("title_word_count");
        RuleLimit occurrences = limitOf("title_repeated_words");
        String atLimit = IntStream.range(0, words.value()).mapToObj(i -> "word" + i).collect(java.util.stream.Collectors.joining(" "));

        assertThat(words.unit()).isEqualTo(Limit.Unit.WORDS);
        assertThat(ruleIds(EtsyTestData.listing(atLimit, List.of("mug")))).doesNotContain("title_word_count");
        assertThat(ruleIds(EtsyTestData.listing(atLimit + " more", List.of("mug")))).contains("title_word_count");

        assertThat(occurrences.unit()).isEqualTo(Limit.Unit.OCCURRENCES);
        String repeatedAtLimit = String.join(" ", java.util.Collections.nCopies(occurrences.value(), "mug"));
        assertThat(ruleIds(EtsyTestData.listing(repeatedAtLimit, List.of("mug")))).doesNotContain("title_repeated_words");
        assertThat(ruleIds(EtsyTestData.listing(repeatedAtLimit + " mug", List.of("mug")))).contains("title_repeated_words");
    }

    private static List<String> tags(int count) {
        return IntStream.range(0, count).mapToObj(i -> "tag" + i).toList();
    }
}
