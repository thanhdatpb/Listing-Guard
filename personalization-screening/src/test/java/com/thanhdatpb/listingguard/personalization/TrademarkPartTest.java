package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The trademark half of a screening (ADR-016): the fictional marks of the DEMO catalog, never a real one. */
class TrademarkPartTest {

    private final PersonalizationRuleset ruleset = Fixtures.ruleset();
    private final PersonalizationScreener screener = new PersonalizationScreener(ruleset);

    @Test
    void aCatalogMarkTypedAsAValueIsHighWithItsFieldAndOffsets() {
        ScreeningResult result = screener.screen(Fixtures.input(Fixtures.MARK), Fixtures.scanner());

        TrademarkPart trademark = result.trademark();
        assertThat(trademark.status()).isEqualTo(TrademarkStatus.COMPLETE);
        assertThat(trademark.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(trademark.catalogVersion()).isEqualTo("catalog-test-1");
        assertThat(trademark.rulesetVersion()).isEqualTo(Fixtures.trademarkRuleset().version());
        assertThat(trademark.findings()).hasSize(1);
        TrademarkHit hit = trademark.findings().get(0);
        assertThat(hit.ruleId()).isEqualTo("catalog_exact");
        assertThat(hit.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(hit.matchType()).isEqualTo(MatchType.EXACT);
        assertThat(hit.field()).isEqualTo("personalization[0]");
        assertThat(hit.mark()).isEqualTo(Fixtures.MARK);
        assertThat(hit.start()).isZero();
        assertThat(hit.end()).isEqualTo(Fixtures.MARK.length());
        assertThat(result.findings()).isEmpty();
        // a trademark finding does not make a character finding, and the two halves stand next to each other
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void theOffsetsAreInTheTextAsSentIncludingTheSpacesTheScannerStrips() {
        String text = "  " + Fixtures.MARK.toUpperCase() + " ";

        ScreeningResult result = screener.screen(Fixtures.input(text), Fixtures.scanner());

        TrademarkHit hit = result.trademark().findings().get(0);
        assertThat(hit.start()).isEqualTo(2);
        assertThat(hit.end()).isEqualTo(2 + Fixtures.MARK.length());
        assertThat(text.substring(hit.start(), hit.end())).isEqualToIgnoringCase(Fixtures.MARK);
        assertThat(hit.message()).contains("ký tự thứ 3 đến " + (2 + Fixtures.MARK.length()));
        assertThat(result.findings()).extracting(TextFinding::ruleId).containsExactly(RuleIds.WHITESPACE_ISSUE,
                RuleIds.WHITESPACE_ISSUE);
    }

    @Test
    void theBuyersTextIsNeverCopiedIntoAHit() {
        String typed = "ZIBBLEFRAX";
        PersonalizationInput input = new PersonalizationInput(null, null,
                List.of(new PersonalizationInput.Value("name", typed + " TEAM")));

        ScreeningResult result = screener.screen(input, Fixtures.scanner());

        TrademarkHit hit = result.trademark().findings().get(0);
        assertThat(hit.message()).doesNotContain(typed).doesNotContain("TEAM").contains("'" + Fixtures.MARK + "'")
                .contains("personalization[0] (name)");
        assertThat(hit.suggestion()).doesNotContain(typed);
        assertThat(hit.toString()).doesNotContain(typed);
        assertThat(hit.label()).isEqualTo("name");
    }

    @Test
    void aMisspelledMarkIsAMediumFuzzyHitWithAScoreAndNoBuyerText() {
        ScreeningResult result = screener.screen(Fixtures.input("Zibblefrak"), Fixtures.scanner());

        TrademarkHit hit = result.trademark().findings().get(0);
        assertThat(hit.ruleId()).isEqualTo("catalog_fuzzy");
        assertThat(hit.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(hit.score()).isBetween(0.8, 1.0);
        assertThat(hit.message()).contains("độ tương đồng").doesNotContain("Zibblefrak ");
        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void aPlainNameIsLowAndComplete() {
        ScreeningResult result = screener.screen(Fixtures.input("MAX", "10"), Fixtures.scanner());

        assertThat(result.trademark().status()).isEqualTo(TrademarkStatus.COMPLETE);
        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.trademark().findings()).isEmpty();
    }

    @Test
    void aCatalogThatIsNotServedLeavesTheTrademarkPartUnknownAndTheCharacterRulesRunning() {
        String emoji = Fixtures.codePoint(0x1F600);

        ScreeningResult result = screener.screen(Fixtures.input("Max " + emoji), null);

        assertThat(result.trademark().status()).isEqualTo(TrademarkStatus.NOT_CONFIGURED);
        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(result.trademark().catalogVersion()).isNull();
        assertThat(result.trademark().findings()).isEmpty();
        assertThat(result.findings()).extracting(TextFinding::ruleId).containsExactly(RuleIds.UNSUPPORTED_CHARACTERS);
        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).isEmpty();
    }

    @Test
    void aTrademarkRuleThatFailsKeepsTheFindingsAndNeverTurnsUnknownIntoLow() {
        TrademarkRuleset trademarkRuleset = Fixtures.trademarkRuleset();
        TrademarkCatalog broken = new TrademarkCatalog() {
            @Override
            public String version() {
                return "catalog-broken";
            }

            @Override
            public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
                throw new IllegalStateException("catalog down");
            }

            @Override
            public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms, double threshold) {
                throw new IllegalStateException("catalog down");
            }
        };
        TrademarkScanner scanner = new TrademarkScanner(trademarkRuleset, broken);

        ScreeningResult result = screener.screen(Fixtures.input("MAX"), scanner);

        assertThat(result.trademark().status()).isEqualTo(TrademarkStatus.INCOMPLETE);
        assertThat(result.trademark().failedRuleIds()).isNotEmpty();
        assertThat(result.trademark().riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    }

    @Test
    void aPlaceAndASportTermAreOneFindingInOneValueButNotAcrossValues() {
        TrademarkRuleset trademarkRuleset = Fixtures.trademarkRuleset();
        String place = String.join(" ", trademarkRuleset.lexicon().places().get(0));
        String sport = String.join(" ", trademarkRuleset.lexicon().sportTerms().get(0));

        ScreeningResult together = screener.screen(Fixtures.input(place + " " + sport), Fixtures.scanner());
        ScreeningResult apart = screener.screen(Fixtures.input(place, sport), Fixtures.scanner());

        assertThat(together.trademark().findings()).extracting(TrademarkHit::ruleId).containsExactly("geo_sport_combination");
        assertThat(together.trademark().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(together.trademark().findings().get(0).message()).doesNotContain(place).doesNotContain(sport);
        assertThat(apart.trademark().findings()).isEmpty();
        assertThat(apart.trademark().riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void theHitsOfTwoValuesCarryTheirOwnIndexAndLabel() {
        PersonalizationInput input = new PersonalizationInput(null, null, List.of(
                new PersonalizationInput.Value("name", "MAX"),
                new PersonalizationInput.Value("team", Fixtures.OTHER_MARK)));

        ScreeningResult result = screener.screen(input, Fixtures.scanner());

        assertThat(result.trademark().findings()).hasSize(1);
        TrademarkHit hit = result.trademark().findings().get(0);
        assertThat(hit.valueIndex()).isEqualTo(1);
        assertThat(hit.field()).isEqualTo("personalization[1]");
        assertThat(hit.label()).isEqualTo("team");
        assertThat(hit.mark()).isEqualTo(Fixtures.OTHER_MARK);
    }

    @Test
    void theShippedTrademarkRulesetScansTagsAndNoRuleDependsOnTheFieldName() {
        // the adapter hands the values over as the items of `tags` (ADR-016): this is the coupling, and it is held here
        assertThat(Fixtures.trademarkRuleset().matching().scanFields()).contains("tags");
    }

    @Test
    void theTrademarkFingerprintTakesPartInTheAnalysisVersion() {
        TrademarkScanner scanner = Fixtures.scanner();
        String withCatalog = screener.fingerprint("etsy", scanner.fingerprint(Map.of("catalog_version", "a")).value()).value();
        String otherCatalog = screener.fingerprint("etsy", scanner.fingerprint(Map.of("catalog_version", "b")).value()).value();
        String none = screener.fingerprint("etsy", null).value();

        assertThat(withCatalog).isNotEqualTo(otherCatalog).isNotEqualTo(none);
        assertThat(none).isEqualTo(screener.fingerprint("etsy", AnalysisFingerprint.NOT_CONFIGURED).value());
    }
}
