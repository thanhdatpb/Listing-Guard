package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.color;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.place;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.ruleIds;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scanTitle;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.sport;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.rules.Candidate;
import com.thanhdatpb.listingguard.trademark.rules.ScanContext;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRule;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRules;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ResultAggregationTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();

    /** A catalog whose lookups can be made to fail. */
    private static final class FlakyCatalog implements TrademarkCatalog {
        private final TrademarkCatalog inner;
        private final boolean failExact;
        private final boolean failFuzzy;

        FlakyCatalog(TrademarkCatalog inner, boolean failExact, boolean failFuzzy) {
            this.inner = inner;
            this.failExact = failExact;
            this.failFuzzy = failFuzzy;
        }

        @Override
        public String version() {
            return inner.version();
        }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> forms) {
            if (failExact) {
                throw new IllegalStateException("catalog down");
            }
            return inner.findExact(forms);
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> forms, double threshold) {
            if (failFuzzy) {
                throw new IllegalStateException("catalog down");
            }
            return inner.fuzzyCandidates(forms, threshold);
        }
    }

    private TrademarkScanner scannerWithCatalog(boolean failExact, boolean failFuzzy, String... marks) {
        return new TrademarkScanner(ruleset, new FlakyCatalog(ScannerFixtures.catalog(ruleset, marks), failExact,
                failFuzzy));
    }

    private TrademarkScanner scannerWithFailingRule(String ruleId, String... marks) {
        List<TrademarkRule> rules = TrademarkRules.create(ruleset).stream().<TrademarkRule>map(rule ->
                rule.id().equals(ruleId) ? new TrademarkRule() {
                    @Override
                    public String id() {
                        return ruleId;
                    }

                    @Override
                    public List<Candidate> apply(ScanContext context, List<Candidate> soFar) {
                        throw new IllegalStateException("rule broke");
                    }
                } : rule).toList();
        return new TrademarkScanner(ruleset, ScannerFixtures.catalog(ruleset, marks), rules);
    }

    private static TrademarkFinding finding(RiskLevel risk) {
        return new TrademarkFinding("r", risk, "m", "s", null);
    }

    @Test
    void aCompleteScanWithNoFindingsIsLow() {
        TrademarkResult result = scanTitle(ScannerFixtures.scanner("Zorvex"), "Plain mug");

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.failedRuleIds()).isEmpty();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void theRiskLevelIsTheHighestFinding() {
        TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex");
        TrademarkResult result = scanTitle(scanner, "Zorvx and " + place(ruleset, 0) + " " + sport(ruleset, 0)
                + " and Zorvex");

        assertThat(result.findings()).extracting(TrademarkFinding::risk).contains(ruleset.catalogFuzzy().risk(),
                ruleset.catalogExact().risk());
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void theRiskLevelMatrix() {
        for (RiskLevel level : List.of(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH)) {
            assertThat(TrademarkScanner.riskLevel(List.of(finding(RiskLevel.LOW), finding(level)),
                    Completeness.COMPLETE)).isEqualTo(level);
        }
        assertThat(TrademarkScanner.riskLevel(List.of(), Completeness.COMPLETE)).isEqualTo(RiskLevel.LOW);
        assertThat(TrademarkScanner.riskLevel(List.of(finding(RiskLevel.MEDIUM)), Completeness.INCOMPLETE))
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(TrademarkScanner.riskLevel(List.of(finding(RiskLevel.LOW), finding(RiskLevel.HIGH)),
                Completeness.INCOMPLETE)).isEqualTo(RiskLevel.HIGH);
        assertThat(TrademarkScanner.riskLevel(List.of(), Completeness.INCOMPLETE)).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(TrademarkScanner.riskLevel(List.of(finding(RiskLevel.LOW)), Completeness.INCOMPLETE))
                .isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void anIncompleteScanKeepsTheFindingsAndTheKnownRisk() {
        TrademarkScanner scanner = scannerWithFailingRule(RuleIds.GEO_SPORT_COMBINATION, "Zorvex");

        TrademarkResult result = scanTitle(scanner, "Zorvex " + place(ruleset, 0) + " " + sport(ruleset, 0));

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
    }

    @Test
    void aKnownMediumRiskStaysAtLeastMediumWhenARuleFails() {
        TrademarkScanner scanner = scannerWithFailingRule(RuleIds.CATALOG_EXACT, "Zorvex");

        TrademarkResult result = scanTitle(scanner, "Zorvx");

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_FUZZY);
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogFuzzy().risk());
        assertThat(result.failedRuleIds()).contains(RuleIds.CATALOG_EXACT);
    }

    @Test
    void whenNothingIsEstablishedTheRiskIsUnknownNeverLow() {
        TrademarkScanner scanner = scannerWithFailingRule(RuleIds.GEO_SPORT_COMBINATION, "Zorvex");

        TrademarkResult result = scanTitle(scanner, "Plain mug");

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.findings()).isEmpty();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void aLowInformationalFindingDoesNotEstablishARiskWhenARuleFails() {
        TrademarkScanner scanner = scannerWithFailingRule(RuleIds.GEO_SPORT_COMBINATION, "Zorvex");

        TrademarkResult result = scanTitle(scanner, color(ruleset, 0) + " " + color(ruleset, 1));

        assertThat(ruleIds(result)).containsExactly(RuleIds.PURE_COLOR_DESCRIPTION);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void aCatalogOutageKeepsContextFindingsAndReportsEveryRuleThatNeededTheCatalog() {
        TrademarkScanner scanner = scannerWithCatalog(true, false, "Zorvex");

        TrademarkResult result = scanTitle(scanner, place(ruleset, 0) + " " + sport(ruleset, 0));

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactly(RuleIds.CATALOG_EXACT, RuleIds.CATALOG_PHRASE,
                RuleIds.CATALOG_FUZZY, RuleIds.COLOR_NAME_CONTAINS_MARK, RuleIds.PURE_COLOR_DESCRIPTION);
        assertThat(ruleIds(result)).containsExactly(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(result.riskLevel()).isEqualTo(ruleset.geoSport().risk());
    }

    @Test
    void aCatalogOutageNeverProducesACleanLowResult() {
        TrademarkScanner scanner = scannerWithCatalog(true, true, "Zorvex");

        TrademarkResult result = scanTitle(scanner, "Plain mug");

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    }

    @Test
    void aColorDescriptionIsNotCalledFreeOfTrademarksWhenTheCatalogFailed() {
        TrademarkScanner scanner = scannerWithCatalog(true, true, "Zorvex");

        TrademarkResult result = scanTitle(scanner, color(ruleset, 0) + " " + color(ruleset, 1));

        assertThat(result.findings()).isEmpty();
        assertThat(result.failedRuleIds()).contains(RuleIds.PURE_COLOR_DESCRIPTION);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void aFuzzyLookupFailureKeepsTheExactFindings() {
        TrademarkScanner scanner = scannerWithCatalog(false, true, "Zorvex");

        TrademarkResult result = scanTitle(scanner, "Zorvex");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(result.failedRuleIds()).containsExactly(RuleIds.CATALOG_FUZZY, RuleIds.COLOR_NAME_CONTAINS_MARK,
                RuleIds.PURE_COLOR_DESCRIPTION);
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
    }

    @Test
    void theResultCarriesTheInputHashVersionsAndDisclaimer() {
        TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex");
        TrademarkCheckInput input = TrademarkCheckInput.ofTitle("Zorvex");

        TrademarkResult result = scanner.scan(input);

        assertThat(result.inputHash()).isEqualTo(CanonicalInput.of(input).hash());
        assertThat(result.rulesetVersion()).isEqualTo(ruleset.version());
        assertThat(result.catalogVersion()).isEqualTo(ScannerFixtures.CATALOG_VERSION);
        assertThat(result.disclaimer()).isEqualTo(ruleset.disclaimer());
    }

    @Test
    void failedRuleIdsFollowTheRulesetOrder() {
        TrademarkScanner scanner = scannerWithCatalog(true, true);

        TrademarkResult result = scanTitle(scanner, "x");

        Predicate<String> declared = ruleset.ruleOrder()::contains;
        assertThat(result.failedRuleIds()).allMatch(declared);
        assertThat(result.failedRuleIds()).isSortedAccordingTo((a, b) ->
                Integer.compare(ruleset.ruleIndex(a), ruleset.ruleIndex(b)));
    }
}
