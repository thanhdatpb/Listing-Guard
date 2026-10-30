package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.CATALOG_VERSION;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.ruleIds;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scanTitle;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scanner;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogMatchingTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex", "Quillmark",
            "Blue Harbor Tools", "Brask");

    private static char chr(int codePoint) {
        return (char) codePoint;
    }

    @Test
    void exactMatchIsReportedAtTheConfiguredRiskWithEvidence() {
        TrademarkResult result = scanTitle(scanner, "Big Zorvex Shirt");

        assertThat(result.findings()).hasSize(1);
        TrademarkFinding finding = result.findings().get(0);
        assertThat(finding.ruleId()).isEqualTo(RuleIds.CATALOG_EXACT);
        assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogExact().risk());
        Evidence evidence = finding.evidence();
        assertThat(evidence.field()).isEqualTo("title");
        assertThat("Big Zorvex Shirt".substring(evidence.start(), evidence.end())).isEqualTo("Zorvex");
        assertThat(evidence.snippet()).isEqualTo("Zorvex");
        assertThat(evidence.normalizedToken()).isEqualTo("zorvex");
        assertThat(evidence.matchedMark()).isEqualTo("Zorvex");
        assertThat(evidence.matchType()).isEqualTo(MatchType.EXACT);
        assertThat(evidence.score()).isEqualTo(1.0);
        assertThat(evidence.catalogVersion()).isEqualTo(CATALOG_VERSION);
        assertThat(evidence.ruleId()).isEqualTo(RuleIds.CATALOG_EXACT);
        assertThat(evidence.insideColorPhrase()).isFalse();
        assertThat(finding.message()).contains("Zorvex").contains("title");
    }

    @Test
    void aMultiTokenMarkIsAPhraseMatch() {
        TrademarkResult result = scanTitle(scanner, "Premium BLUE harbor-tools set");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_PHRASE);
        Evidence evidence = result.findings().get(0).evidence();
        assertThat(evidence.matchType()).isEqualTo(MatchType.PHRASE);
        assertThat(evidence.normalizedToken()).isEqualTo("blue harbor tools");
        assertThat(evidence.matchedMark()).isEqualTo("Blue Harbor Tools");
        assertThat(result.riskLevel()).isEqualTo(ruleset.catalogPhrase().risk());
    }

    @Test
    void aPhraseNeedsAllItsTokensInOneField() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput("blue harbor", List.of("tools"),
                "tools", null, null, null));

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void aMarkInsideALongerWordIsNotAnExactMatch() {
        for (String title : List.of("Zorvexa", "xzorvex", "Zorvexity", "unzorvex")) {
            assertThat(scanTitle(scanner, title).findings())
                    .as(title).noneMatch(f -> f.evidence().matchType() == MatchType.EXACT);
        }
        assertThat(scanTitle(scanner, "Zorvexity").findings()).isEmpty();
    }

    @Test
    void caseAndPunctuationDoNotHideAMark() {
        for (String title : List.of("ZORVEX", "zorvex,", "(Zorvex)", "Zorvex!!!")) {
            assertThat(ruleIds(scanTitle(scanner, title))).as(title).containsExactly(RuleIds.CATALOG_EXACT);
        }
    }

    @Test
    void diacriticsDoNotHideAMark() {
        String composed = Normalizer.normalize("Zörvéx", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize("Zörvéx", Normalizer.Form.NFD);

        assertThat(ruleIds(scanTitle(scanner, composed))).containsExactly(RuleIds.CATALOG_EXACT);
        assertThat(ruleIds(scanTitle(scanner, decomposed))).containsExactly(RuleIds.CATALOG_EXACT);
    }

    @Test
    void homoglyphsAndLeetDoNotHideAMark() {
        String cyrillicO = String.valueOf(chr(0x043E));
        String greekO = String.valueOf(chr(0x03BF));

        for (String title : List.of("Z0rvex", "z" + cyrillicO + "rvex", "z" + greekO + "rvex", "Zorv3x", "Z0rv3x")) {
            TrademarkResult result = scanTitle(scanner, title);
            assertThat(ruleIds(result)).as(title).containsExactly(RuleIds.CATALOG_EXACT);
            assertThat(result.findings().get(0).evidence().snippet()).isEqualTo(title);
        }
    }

    @Test
    void aMisspellingIsAFuzzyMatchScoredInJava() {
        TrademarkResult result = scanTitle(scanner, "Cool Zorvx hoodie");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_FUZZY);
        Evidence evidence = result.findings().get(0).evidence();
        assertThat(evidence.matchType()).isEqualTo(MatchType.FUZZY);
        assertThat(evidence.matchedMark()).isEqualTo("Zorvex");
        assertThat(evidence.normalizedToken()).isEqualTo("zorvx");
        assertThat(evidence.score()).isEqualTo(5.0 / 6.0).isGreaterThanOrEqualTo(ruleset.matching().finalThreshold());
        assertThat(result.findings().get(0).risk()).isEqualTo(ruleset.catalogFuzzy().risk());
        assertThat(result.findings().get(0).message()).contains("83%");
    }

    @Test
    void aSwappedPairOfLettersIsOneEdit() {
        TrademarkResult result = scanTitle(scanner, "Zorvxe");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_FUZZY);
        assertThat(result.findings().get(0).evidence().score()).isEqualTo(5.0 / 6.0);
    }

    @Test
    void aScoreBelowTheFinalThresholdIsNotReported() {
        assertThat(scanTitle(scanner, "Zxrvax").findings()).isEmpty();
    }

    @Test
    void aScoreExactlyAtTheFinalThresholdIsReported() {
        // "brisk" is one substitution from the five-letter mark "brask": 4/5, which is the ruleset threshold
        assertThat(ruleset.matching().finalThreshold()).isEqualTo(0.8);
        assertThat(ruleIds(scanTitle(scanner, "Brisk"))).containsExactly(RuleIds.CATALOG_FUZZY);

        TrademarkRuleset stricter = TestRulesets.variant("final_threshold: 0.80", "final_threshold: 0.81");
        assertThat(scanTitle(ScannerFixtures.scanner(stricter, "Brask"), "Brisk").findings()).isEmpty();
    }

    @Test
    void anExactMatchIsNotAlsoReportedAsFuzzy() {
        TrademarkResult result = scanTitle(scanner, "Zorvex");

        assertThat(ruleIds(result)).containsExactly(RuleIds.CATALOG_EXACT);
    }

    @Test
    void shortGramsAreOnlyMatchedExactly() {
        TrademarkScanner shortMark = ScannerFixtures.scanner(ruleset, "Zork");

        assertThat(scanTitle(shortMark, "Zork").findings()).hasSize(1);
        assertThat(scanTitle(shortMark, "Zorx").findings()).isEmpty();
    }

    @Test
    void everyOccurrenceIsReported() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput("Zorvex and Zorvex", List.of("zorvex", "plain"),
                "x zorvex", "Quillmark", "Quillmark shop", null));

        assertThat(result.findings()).extracting(f -> f.evidence().field())
                .containsExactly("title", "title", "tags[0]", "description", "brand", "shop_name");
        assertThat(result.findings().get(0).evidence().start()).isLessThan(result.findings().get(1).evidence().start());
    }

    @Test
    void onlyTheFieldsTheRulesetScansAreRead() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput("plain", List.of(), "plain", "plain", "plain",
                "Zorvex"));

        assertThat(ruleset.matching().scanFields()).doesNotContain("category");
        assertThat(result.findings()).isEmpty();

        TrademarkRuleset withCategory = TestRulesets.variant("    - shop_name\n", "    - shop_name\n    - category\n");
        TrademarkResult scanned = ScannerFixtures.scanner(withCategory, "Zorvex").scan(
                new TrademarkCheckInput("plain", List.of(), "plain", "plain", "plain", "Zorvex"));
        assertThat(scanned.findings()).extracting(f -> f.evidence().field()).containsExactly("category");
    }

    @Test
    void evidenceSpansReferToTheCanonicalTextNotTheSubmittedText() {
        String submitted = "   " + Normalizer.normalize("Café Zorvex", Normalizer.Form.NFD) + "  ";
        String canonical = Normalizer.normalize(submitted, Normalizer.Form.NFC).strip();

        Evidence evidence = scanTitle(scanner, submitted).findings().get(0).evidence();

        assertThat(canonical.substring(evidence.start(), evidence.end())).isEqualTo("Zorvex");
        assertThat(evidence.snippet()).isEqualTo("Zorvex");
    }

    @Test
    void snippetsAreBoundedByTheRuleset() {
        TrademarkRuleset shortSnippets = TestRulesets.variant("max_snippet_chars: 80", "max_snippet_chars: 4");
        TrademarkScanner shortScanner = ScannerFixtures.scanner(shortSnippets, "Blue Harbor Tools");

        Evidence evidence = scanTitle(shortScanner, "blue harbor tools").findings().get(0).evidence();

        assertThat(evidence.snippet()).isEqualTo("blue");
        assertThat(evidence.end() - evidence.start()).isEqualTo("blue harbor tools".length());
    }

    /** Records what the scanner asks the catalog, to prove only bounded grams are looked up. */
    private static final class SpyCatalog implements TrademarkCatalog {
        final TrademarkCatalog inner;
        final List<String> exactForms = new ArrayList<>();
        final List<String> fuzzyForms = new ArrayList<>();
        final List<Double> thresholds = new ArrayList<>();

        SpyCatalog(TrademarkCatalog inner) {
            this.inner = inner;
        }

        @Override
        public String version() {
            return inner.version();
        }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> forms) {
            exactForms.addAll(forms);
            return inner.findExact(forms);
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> forms, double threshold) {
            fuzzyForms.addAll(forms);
            thresholds.add(threshold);
            return inner.fuzzyCandidates(forms, threshold);
        }
    }

    @Test
    void theCatalogIsOnlyAskedAboutBoundedTokensAndNgrams() {
        SpyCatalog spy = new SpyCatalog(ScannerFixtures.catalog(ruleset, "Zorvex", "Blue Harbor Tools"));
        TrademarkScanner spied = new TrademarkScanner(ruleset, spy);
        String title = "premium cotton unisex hoodie with a very long descriptive title about zorvx and more words";
        String description = String.join(" ", java.util.Collections.nCopies(60, "soft warm fabric"));

        spied.scan(new TrademarkCheckInput(title, List.of("gift"), description, null, null, null));

        int maxTokens = ruleset.matching().maxNgramTokens();
        assertThat(spy.exactForms).isNotEmpty().allSatisfy(form ->
                assertThat(form.split(" ").length).isLessThanOrEqualTo(maxTokens));
        assertThat(spy.exactForms).doesNotContain(title, description);
        assertThat(spy.fuzzyForms).isNotEmpty().allSatisfy(form -> {
            assertThat(form.split(" ").length).isLessThanOrEqualTo(maxTokens);
            assertThat(form.length()).isBetween(ruleset.matching().fuzzyMinChars(), ruleset.matching().fuzzyMaxChars());
        });
        assertThat(spy.fuzzyForms).doesNotContain(title, description);
        assertThat(spy.thresholds).containsOnly(ruleset.matching().candidateThreshold());
        assertThat(spy.exactForms).doesNotHaveDuplicates();
    }

    @Test
    void theNgramLengthComesFromTheRuleset() {
        TrademarkRuleset unigramsOnly = TestRulesets.variant("max_ngram_tokens: 3", "max_ngram_tokens: 1");
        TrademarkScanner unigrams = ScannerFixtures.scanner(unigramsOnly, "Blue Harbor Tools");

        assertThat(scanTitle(unigrams, "blue harbor tools").findings()).isEmpty();
        assertThat(ruleIds(scanTitle(scanner, "blue harbor tools"))).containsExactly(RuleIds.CATALOG_PHRASE);
    }

    @Test
    void theResultIsDeterministic() {
        TrademarkCheckInput input = new TrademarkCheckInput("Zorvex Zorvx Quillmarc", List.of("blue harbor tools"),
                "Zorvex", null, null, null);

        assertThat(scanner.scan(input)).isEqualTo(scanner.scan(input));
    }

    @Test
    void aMarkInSearchTermsAndInABulletPointIsFoundWithTheRightSpanAndFieldName() {
        String searchTerms = "gift  mug zorvex premium";
        String bullet = "  Soft cotton, Quillmark inside";
        TrademarkCheckInput input = new TrademarkCheckInput("plain", List.of(), List.of("nothing here", bullet),
                "plain", null, null, searchTerms, null);

        TrademarkResult result = scanner.scan(input);

        assertThat(ruleset.matching().scanFields()).contains("bullet_points", "search_terms");
        assertThat(result.findings()).extracting(f -> f.evidence().field())
                .containsExactly("bullet_points[1]", "search_terms");
        Evidence inBullet = result.findings().get(0).evidence();
        assertThat(bullet.strip().substring(inBullet.start(), inBullet.end())).isEqualTo("Quillmark");
        assertThat(inBullet.matchedMark()).isEqualTo("Quillmark");
        Evidence inSearch = result.findings().get(1).evidence();
        assertThat(searchTerms.substring(inSearch.start(), inSearch.end())).isEqualTo("zorvex");
        assertThat(inSearch.matchType()).isEqualTo(MatchType.EXACT);
    }

    @Test
    void bulletPointsAreSeparateFieldsAndAreNotJoinedIntoAPhrase() {
        TrademarkResult result = scanner.scan(new TrademarkCheckInput("plain", List.of(),
                List.of("blue harbor", "tools"), "plain", null, null, "tools", null));

        assertThat(result.findings()).isEmpty();
    }
}
