package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-009 ("Product classes, source links and generic words") added three optional sections to the trademark ruleset and
 * changed the scanner. The personalization pack hands the scanner the values of an order as {@code tags}, names no product
 * type and no brand, so a screening must come out exactly as it did on the ruleset before the sections existed (ADR-016).
 */
class TrademarkRulesetUnchangedTest {

    private static final String OLDER = "rulesets/trademark-risk-2026-11.yaml";
    private static final String NEWER = "rulesets/trademark-risk-2026-11b.yaml";

    private final PersonalizationScreener screener = new PersonalizationScreener(Fixtures.ruleset());

    /**
     * The fictional marks of the DEMO catalog, one with a Nice class that no product type of the newer ruleset lists and a
     * source the newer ruleset can link, one with neither: if the adapter passed a product type or read a link, the
     * newer ruleset would answer differently.
     */
    private static TrademarkScanner scanner(TrademarkRuleset ruleset) {
        TextNormalizer normalizer = new TextNormalizer(ruleset.normalization());
        List<CatalogMark> marks = List.of(
                new CatalogMark(normalizer.normalizeMark(Fixtures.MARK), Fixtures.MARK, List.of(45), MarkStatus.REGISTERED,
                        false, List.of("uspto:12345678")),
                new CatalogMark(normalizer.normalizeMark(Fixtures.OTHER_MARK), Fixtures.OTHER_MARK, List.of(), null,
                        false, List.of()));
        return new TrademarkScanner(ruleset, new InMemoryTrademarkCatalog("catalog-test-1", marks));
    }

    private static String text(String path) {
        try (InputStream in = TrademarkRulesetUnchangedTest.class.getResourceAsStream("/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The result with the trademark ruleset version blanked: the one thing that is meant to differ. */
    private static ScreeningResult withoutTrademarkRulesetVersion(ScreeningResult result) {
        TrademarkPart part = result.trademark();
        return new ScreeningResult(result.inputHash(), result.rulesetVersion(), result.printProfile(),
                result.completeness(), result.findings(), result.failedRuleIds(),
                new TrademarkPart(part.status(), part.riskLevel(), part.catalogVersion(), null, part.failedRuleIds(),
                        part.findings()),
                result.disclaimer());
    }

    @Test
    void aScreeningIsTheSameOnTheRulesetBeforeAndAfterTheOptionalSections() {
        TrademarkRuleset older = TrademarkRulesetLoader.loadClasspath(OLDER);
        TrademarkRuleset newer = TrademarkRulesetLoader.loadClasspath(NEWER);
        List<PersonalizationInput> inputs = List.of(
                Fixtures.input(Fixtures.MARK),
                Fixtures.input(Fixtures.MARK.toUpperCase()),
                Fixtures.input("  " + Fixtures.MARK + " "),
                Fixtures.input("Zibblefrx"),
                Fixtures.input(Fixtures.OTHER_MARK + " Blue"),
                Fixtures.input("Northvale baseball"),
                Fixtures.input("Royal Blue White Pinstripe"),
                Fixtures.input("Max " + Fixtures.codePoint(0x1F600)),
                Fixtures.input(Fixtures.MARK, "10", Fixtures.OTHER_MARK),
                Fixtures.input("MAX", "10"),
                Fixtures.input(""));

        for (PersonalizationInput input : inputs) {
            ScreeningResult before = screener.screen(input, scanner(older));
            ScreeningResult after = screener.screen(input, scanner(newer));

            assertThat(after.trademark().rulesetVersion()).isEqualTo(newer.version());
            assertThat(before.trademark().rulesetVersion()).isEqualTo(older.version());
            assertThat(withoutTrademarkRulesetVersion(after)).as("%s", input.values())
                    .isEqualTo(withoutTrademarkRulesetVersion(before));
        }
    }

    @Test
    void theMarkWithAClassOutsideEveryProductTypeIsStillHighAndItsHitIsUnchanged() {
        TrademarkRuleset newer = TrademarkRulesetLoader.loadClasspath(NEWER);

        ScreeningResult result = screener.screen(Fixtures.input(Fixtures.MARK), scanner(newer));

        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.trademark().findings()).singleElement().satisfies(hit -> {
            assertThat(hit.risk()).isEqualTo(RiskLevel.HIGH);
            assertThat(hit.sourceIds()).containsExactly("uspto:12345678");
        });
    }

    /**
     * What a reviewed list would do to buyer text (an open question of ADR-009): the adapter names no brand field, so a word
     * that is listed in {@code generic_words} never has context here and a hit on it is capped. The shipped list is empty, so
     * nothing changes today.
     */
    @Test
    void aWordTheMaintainerLatersListsAsGenericWouldBeCappedForBuyerTextToo() {
        String listed = text(NEWER).replace("  words: []", "  words:\n    - " + Fixtures.MARK);
        TrademarkRuleset withList = TrademarkRulesetLoader.parse(listed);
        assertThat(TrademarkRulesetLoader.loadClasspath(NEWER).genericWords().words()).isEmpty();

        ScreeningResult result = screener.screen(Fixtures.input(Fixtures.MARK), scanner(withList));

        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.LOW);
    }
}
