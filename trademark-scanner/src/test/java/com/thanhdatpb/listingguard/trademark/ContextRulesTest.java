package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.color;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.filler;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.pattern;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.place;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.ruleIds;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scanTitle;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scanner;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.sport;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-009 "Context rules for color-descriptive listings". Lists, distance and levels come from the test ruleset. */
class ContextRulesTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TrademarkScanner scanner = scanner(ruleset, "Zorvex", "Crimson");
    private final String place = place(ruleset, 0);
    private final String sport = sport(ruleset, 0);
    private final int distance = ruleset.geoSport().maxTokenDistance();

    // --- geographic + sport combination ---

    @Test
    void aPlaceNextToASportTermIsReportedWithoutAnyCatalogMatch() {
        TrademarkResult result = scanTitle(scanner, place + " " + sport + " Shirt");

        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        TrademarkFinding finding = result.findings().get(0);
        assertThat(finding.risk()).isEqualTo(ruleset.geoSport().risk());
        assertThat(result.riskLevel()).isEqualTo(ruleset.geoSport().risk());
        assertThat(finding.evidence().matchType()).isEqualTo(MatchType.CONTEXT);
        assertThat(finding.evidence().matchedMark()).isNull();
        assertThat(finding.evidence().score()).isNull();
        assertThat(finding.message()).contains(String.valueOf(distance)).contains("1");
    }

    @Test
    void aPlaceAndASportTermAtTheMaximumDistanceAreReported() {
        String title = place + filler(distance - 1) + " " + sport;

        TrademarkResult result = scanTitle(scanner, title);

        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        Evidence evidence = result.findings().get(0).evidence();
        assertThat(title.substring(evidence.start(), evidence.end())).isEqualTo(title);
    }

    @Test
    void oneTokenBeyondTheMaximumDistanceGivesNothing() {
        TrademarkResult result = scanTitle(scanner, place + filler(distance) + " " + sport);

        assertThat(result.findings()).isEmpty();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void theSportTermMayComeFirst() {
        assertThat(ruleIds(scanTitle(scanner, sport + " " + place))).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
    }

    @Test
    void aPlaceAloneOrASportTermAloneGivesNothing() {
        assertThat(scanTitle(scanner, place + " Mug").findings()).isEmpty();
        assertThat(scanTitle(scanner, sport + " Mug").findings()).isEmpty();
        assertThat(scanTitle(scanner, "Mug " + place + " Mug " + sport.split(" ")[0].substring(0, 3)).findings())
                .isEmpty();
    }

    @Test
    void theTwoTermsInDifferentFieldsAreNeverCombined() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput(place, List.of(sport), sport, sport, place,
                null));

        assertThat(result.findings()).isEmpty();
        assertThat(scanner.scan(new TrademarkCheckInput("a", List.of(place, sport), null, null, null, null))
                .findings()).isEmpty();
    }

    @Test
    void multiWordPlacesAndSportTermsAreRecognized() {
        String multiPlace = place(ruleset, 2);
        String multiSport = sport(ruleset, 2);
        assertThat(multiPlace).contains(" ");
        assertThat(multiSport).contains(" ");

        TrademarkResult result = scanTitle(scanner, multiPlace + " " + multiSport);

        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(result.findings().get(0).message()).contains("1");
    }

    @Test
    void theLevelDistanceAndListsComeFromTheRuleset() {
        String title = place + filler(distance - 1) + " " + sport;
        assertThat(ruleIds(scanTitle(scanner, title))).containsExactly(RuleIds.GEO_SPORT_COMBINATION);

        TrademarkRuleset closer = TestRulesets.variant("max_token_distance: " + distance,
                "max_token_distance: " + (distance - 1));
        assertThat(scanTitle(scanner(closer), title).findings()).isEmpty();

        TrademarkRuleset higher = TestRulesets.variant("    risk: MEDIUM\n    # A place and a sport term",
                "    risk: HIGH\n    # A place and a sport term");
        TrademarkResult raised = scanTitle(scanner(higher), place + " " + sport);
        assertThat(raised.riskLevel()).isEqualTo(RiskLevel.HIGH);

        TrademarkRuleset otherPlace = TestRulesets.variant("    - Marrowdale\n", "    - Elsewhere\n");
        assertThat(scanTitle(scanner(otherPlace), place + " " + sport).findings()).isEmpty();
        assertThat(ruleIds(scanTitle(scanner(otherPlace), "Elsewhere " + sport)))
                .containsExactly(RuleIds.GEO_SPORT_COMBINATION);
    }

    // --- color name containing a mark ---

    @Test
    void aMarkInsideAColorPhraseIsScoredLikeTheSameMarkOutsideIt() {
        String color = color(ruleset, 0);

        TrademarkResult inside = scanTitle(scanner, "Zorvex " + color);
        TrademarkResult outside = scanTitle(scanner, "Zorvex Mug");

        assertThat(inside.findings()).hasSize(1);
        TrademarkFinding finding = inside.findings().get(0);
        assertThat(finding.ruleId()).isEqualTo(outside.findings().get(0).ruleId());
        assertThat(finding.risk()).isEqualTo(outside.findings().get(0).risk());
        assertThat(finding.evidence().matchType()).isEqualTo(outside.findings().get(0).evidence().matchType());
        assertThat(finding.evidence().insideColorPhrase()).isTrue();
        assertThat(outside.findings().get(0).evidence().insideColorPhrase()).isFalse();
        assertThat(finding.message()).endsWith(ruleset.colorContainsMark().note());
        assertThat(inside.riskLevel()).isEqualTo(outside.riskLevel());
    }

    @Test
    void aMarkThatIsItselfACommonColorNameIsNotExempt() {
        assertThat(ruleset.lexicon().colorWords()).contains("crimson");

        TrademarkResult result = scanTitle(scanner, "Crimson");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(result.findings().get(0).risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(result.findings().get(0).evidence().insideColorPhrase()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aFuzzyMatchInsideAColorPhraseKeepsItsFuzzyRisk() {
        TrademarkResult result = scanTitle(scanner, "Zorvx " + color(ruleset, 1));

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_FUZZY);
        assertThat(result.findings().get(0).risk()).isEqualTo(ruleset.catalogFuzzy().risk());
        assertThat(result.findings().get(0).evidence().insideColorPhrase()).isTrue();
    }

    @Test
    void theColorNoteNeverLowersARisk() {
        TrademarkResult result = scanTitle(scanner, color(ruleset, 0) + " Zorvex " + pattern(ruleset, 0));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    // --- pure color description ---

    @Test
    void aColorAndPatternOnlyPhraseIsAnInformationalLowFinding() {
        String phrase = color(ruleset, 0) + " " + color(ruleset, 1) + " " + pattern(ruleset, 0);

        TrademarkResult result = scanTitle(scanner, "Mug " + phrase);

        assertThat(ruleIds(result)).containsExactly(RuleIds.PURE_COLOR_DESCRIPTION);
        TrademarkFinding finding = result.findings().get(0);
        assertThat(finding.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(finding.evidence().snippet()).isEqualToIgnoringCase(phrase);
        assertThat(finding.message()).contains("3");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void aColorPhraseWithACatalogTokenIsNotReportedAsPureColor() {
        TrademarkResult result = scanTitle(scanner, color(ruleset, 0) + " " + color(ruleset, 1) + " Zorvex "
                + pattern(ruleset, 0));

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aFuzzyMatchAboveTheFinalThresholdAlsoMakesThePhraseNotPure() {
        TrademarkResult result = scanTitle(scanner, color(ruleset, 0) + " " + color(ruleset, 1) + " Zorvx");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_FUZZY);
    }

    @Test
    void aSingleColorWordOrAPatternOnlyPhraseIsNotReported() {
        assertThat(scanTitle(scanner, "Mug " + color(ruleset, 0)).findings()).isEmpty();
        assertThat(scanTitle(scanner, "Mug " + pattern(ruleset, 0) + " " + pattern(ruleset, 1)).findings()).isEmpty();
    }

    @Test
    void theShortestReportedPhraseComesFromTheRuleset() {
        String twoWords = "Mug " + color(ruleset, 0) + " " + color(ruleset, 1);
        assertThat(ruleIds(scanTitle(scanner, twoWords))).containsExactly(RuleIds.PURE_COLOR_DESCRIPTION);

        TrademarkRuleset longer = TestRulesets.variant("min_tokens: 2", "min_tokens: 3");
        assertThat(scanTitle(scanner(longer), twoWords).findings()).isEmpty();
    }

    @Test
    void colorWordsInDifferentFieldsAreNotOnePhrase() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput(color(ruleset, 0), List.of(color(ruleset, 1)),
                null, null, null, null));

        assertThat(result.findings()).isEmpty();
    }

    // --- precedence ---

    @Test
    void overlappingMatchesReportTheHighestRiskAndNameTheOthers() {
        TrademarkScanner overlapping = scanner(ruleset, place);

        TrademarkResult result = scanTitle(overlapping, place + " " + sport);

        assertThat(ruleset.catalogExact().risk()).isGreaterThan(ruleset.geoSport().risk());
        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(result.findings().get(0).evidence().otherRuleIds()).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aTieInRiskGoesToTheRuleDeclaredFirst() {
        TrademarkRuleset tied = TestRulesets.variant("    risk: MEDIUM\n    # A place and a sport term",
                "    risk: HIGH\n    # A place and a sport term");
        TrademarkResult catalogFirst = scanTitle(scanner(tied, place), place + " " + sport);
        assertThat(ruleIds(catalogFirst)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(catalogFirst.findings().get(0).evidence().otherRuleIds()).containsExactly(RuleIds.GEO_SPORT_COMBINATION);

        String text = TestRulesets.variantText("    risk: MEDIUM\n    # A place and a sport term",
                "    risk: HIGH\n    # A place and a sport term");
        int geo = text.indexOf("  geo_sport_combination:");
        int colorRule = text.indexOf("  color_name_contains_mark:");
        int exact = text.indexOf("  catalog_exact:");
        String geoFirst = text.substring(0, exact) + text.substring(geo, colorRule) + text.substring(exact, geo)
                + text.substring(colorRule);
        TrademarkRuleset reordered = com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader.parse(geoFirst);
        assertThat(reordered.ruleOrder().get(0)).isEqualTo(RuleIds.GEO_SPORT_COMBINATION);

        TrademarkResult result = scanTitle(scanner(reordered, place), place + " " + sport);
        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(result.findings().get(0).evidence().otherRuleIds()).containsExactly(RuleIds.CATALOG_EXACT);
    }

    @Test
    void spansThatDoNotOverlapAreAllReported() {
        TrademarkResult result = scanTitle(scanner, "Zorvex " + filler(3) + " " + place + " " + sport);

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT, RuleIds.GEO_SPORT_COMBINATION);
    }

    // --- a sport word inside a place name ---

    @Test
    void aSportWordInsideAMatchedPlaceNameIsNotASportTerm() {
        String placeWithSport = "Port Hockey";
        assertThat(ruleset.lexicon().places()).contains(List.of("port", "hockey"));
        assertThat(ruleset.lexicon().sportTerms()).contains(List.of("hockey"));

        assertThat(scanTitle(scanner, place + " " + placeWithSport).findings()).isEmpty();
        assertThat(scanTitle(scanner, placeWithSport + " " + place).findings()).isEmpty();
        assertThat(scanTitle(scanner, placeWithSport).findings()).isEmpty();
    }

    @Test
    void aPlaceNameContainingASportWordStillPairsWithARealSportTerm() {
        TrademarkResult result = scanTitle(scanner, "Port Hockey " + sport);

        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(result.findings().get(0).message()).contains("port hockey").contains(sport);
    }

    @Test
    void aSportWordOutsideThePlaceNameStillCounts() {
        String title = "Port Hockey" + filler(distance + 1) + " " + place + " hockey";

        TrademarkResult result = scanTitle(scanner, title);

        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        Evidence evidence = result.findings().get(0).evidence();
        assertThat(title.substring(evidence.start(), evidence.end())).isEqualTo(place + " hockey");
    }
}
