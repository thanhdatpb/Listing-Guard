package com.thanhdatpb.listingguard.api.evaluation.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.Result;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.RuleScore;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the labelled sample set is loaded, scored and compared with its baseline (ADR-032), on cases written here. */
class ListingEvaluatorTest {

    private static Function<String, RulesetConfig> rulesets;
    private static TrademarkScanner scanner;
    private static ListingEvaluator evaluator;

    @TempDir
    Path dir;

    @BeforeAll
    static void load() throws IOException {
        rulesets = ListingEvalGateTest.activeRulesets();
        scanner = DemoCatalogScanner.load(ListingEvalGateTest.trademarkRuleset(), ListingEvalGateTest.DEMO_DIR);
        evaluator = new ListingEvaluator(new ListingAnalyzer(ListingEvalGateTest.normalizers(),
                new RuleEngine(RuleRegistry.withDefaults()), rulesets, marketplace -> scanner), rulesets, scanner);
    }

    private static final String CLEAN = """
              description: A stoneware mug finished with a blue glaze.
              tags: [ceramic mug, stoneware]
              price: 28.00
              quantity: 4
              who_made: i_did
              when_made: made_to_order
              category: Home & Living > Mugs
            """;

    private static ListingEvalCase sample(String id, String extraListing, String expected) {
        return ListingEvalCase.parse("id: " + id + "\nmarketplace: ETSY\nnote: test\nlisting:\n" + extraListing + CLEAN
                + "expected: " + expected + "\n", id);
    }

    private static RuleScore score(Result result, String key) {
        return result.rules().stream().filter(rule -> rule.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void countsTruePositivesFalsePositivesAndFalseNegativesPerRule() {
        Result result = evaluator.evaluate(List.of(
                sample("hit", "  title: Ceramic Coffee Mug Free Shipping\n",
                        "[{rule: title_promotional_terms, field: title}]"),
                sample("unexpected", "  title: Ceramic Coffee Mug on Sale\n", "[]"),
                sample("missed", "  title: Ceramic Coffee Mug\n", "[{rule: title_length}]"),
                sample("mark", "  title: Zibblefrax Ceramic Coffee Mug\n",
                        "[{rule: trademark/catalog_exact, mark: ZIBBLEFRAX}]")));

        RuleScore promotional = score(result, "ETSY/title_promotional_terms");
        assertThat(promotional.truePositives()).isEqualTo(1);
        assertThat(promotional.falsePositives()).isEqualTo(1);
        assertThat(promotional.precision().text()).isEqualTo("1/2 (50.0%)");
        RuleScore length = score(result, "ETSY/title_length");
        assertThat(length.falseNegatives()).isEqualTo(1);
        assertThat(length.precision().value()).isNull();
        assertThat(length.recall().text()).isEqualTo("0/1 (0.0%)");
        assertThat(length.sourceStatus()).isEqualTo("VERIFIED");
        assertThat(score(result, "trademark/catalog_exact").truePositives()).isEqualTo(1);
        assertThat(result.casesPassed()).isEqualTo(2);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void theGateFailsWhenAVerifiedRuleLosesPrecisionAndNotForAnotherRule() {
        Result clean = evaluator.evaluate(List.of(
                sample("long", "  title: \"" + "Mug ".repeat(36).trim() + "x\"\n",
                        "[{rule: title_length}, {rule: title_word_count}, {rule: title_repeated_words}]"),
                sample("promo", "  title: Ceramic Coffee Mug Free Shipping\n", "[{rule: title_promotional_terms}]")));
        ListingEvalBaseline baseline = ListingEvalBaseline.of(clean);
        assertThat(baseline.regressions(clean)).isEmpty();

        // the same listings, labelled so that title_length (verified) and title_promotional_terms (secondary) each get a
        // false positive
        Result worse = evaluator.evaluate(List.of(
                sample("long", "  title: \"" + "Mug ".repeat(36).trim() + "x\"\n",
                        "[{rule: title_word_count}, {rule: title_repeated_words}, {rule: title_length, field: description}]"),
                sample("promo", "  title: Ceramic Coffee Mug Free Shipping\n", "[{rule: title_promotional_terms, field: tags}]")));

        List<ListingEvalBaseline.Regression> regressions = baseline.regressions(worse);
        assertThat(regressions).extracting(ListingEvalBaseline.Regression::key).containsExactly("ETSY/title_length");
        assertThat(ListingEvalReport.markdown(worse, regressions)).contains("Gate: FAILED").contains("ETSY/title_length");
    }

    @Test
    void theBaselineRoundTripsThroughItsFile() {
        Result result = evaluator.evaluate(List.of(sample("promo", "  title: Ceramic Coffee Mug Free Shipping\n",
                "[{rule: title_promotional_terms}]")));
        Path file = dir.resolve("baseline.json");
        ListingEvalBaseline.of(result).write(file);

        ListingEvalBaseline loaded = ListingEvalBaseline.load(file);

        assertThat(loaded).isEqualTo(ListingEvalBaseline.of(result));
        assertThatThrownBy(() -> ListingEvalBaseline.load(dir.resolve("none.json")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("real run");
    }

    @Test
    void theLoaderRefusesWhatCouldQuietlyChangeACase() throws IOException {
        assertThatThrownBy(() -> ListingEvalCase.parse("id: a\nmarketplace: ETSY\nnote: n\nlisting: {}\nexpected: []\n"
                + "expect: []\n", "a")).hasMessageContaining("'expect' is not a key");
        assertThatThrownBy(() -> ListingEvalCase.parse("id: a\nmarketplace: ETSY\nnote: n\nlisting: {}\nexpected: []\n", "b"))
                .hasMessageContaining("must equal the file name");
        assertThatThrownBy(() -> ListingEvalCase.parse("id: a\nmarketplace: WALMART\nnote: n\nlisting: {}\nexpected: []\n",
                "a")).hasMessageContaining("ETSY or AMAZON");
        assertThatThrownBy(() -> ListingEvalCase.parse("id: a\nmarketplace: ETSY\nnote: n\nlisting: {}\n", "a"))
                .hasMessageContaining("'expected' must be a list");
        assertThatThrownBy(() -> ListingEvalCase.parse("id: a\nmarketplace: ETSY\nnote: n\nlisting: {}\n"
                + "expected: [{rule: title_length, mark: X}]\n", "a")).hasMessageContaining("only for a trademark rule");
        assertThatThrownBy(() -> evaluator.evaluate(List.of(sample("typo", "  title: Mug\n", "[{rule: title_lenght}]"))))
                .hasMessageContaining("title_lenght").hasMessageContaining("does not have");
        assertThatThrownBy(() -> evaluator.evaluate(List.of(sample("tm", "  title: Mug\n", "[{rule: trademark/exact}]"))))
                .hasMessageContaining("not a trademark rule");

        Files.writeString(dir.resolve("x.yaml"), "id: y\nmarketplace: ETSY\nnote: n\nlisting: {}\nexpected: []\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ListingEvalCase.loadAll(dir)).hasMessageContaining("x.yaml");
    }
}
