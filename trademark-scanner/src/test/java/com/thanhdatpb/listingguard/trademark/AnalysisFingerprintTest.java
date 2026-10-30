package com.thanhdatpb.listingguard.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-010: a change to any component that can affect findings changes the fingerprint. */
class AnalysisFingerprintTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final Map<String, String> external = Map.of("catalog_version", "catalog-1", "catalog_hash", "abc",
            "pg_major", "16", "pg_trgm_extversion", "1.6");

    private AnalysisFingerprint base() {
        return AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, external);
    }

    private static TrademarkRuleset withPackId(TrademarkRuleset r, String packId) {
        return new TrademarkRuleset(packId, r.version(), r.checkedAt(), r.contentHash(), r.disclaimer(),
                r.normalization(), r.matching(), r.lexicon(), r.ruleOrder(), r.catalogExact(), r.catalogPhrase(),
                r.catalogFuzzy(), r.geoSport(), r.colorContainsMark(), r.pureColor());
    }

    private static TrademarkRuleset withVersion(TrademarkRuleset r, String version) {
        return new TrademarkRuleset(r.packId(), version, r.checkedAt(), r.contentHash(), r.disclaimer(),
                r.normalization(), r.matching(), r.lexicon(), r.ruleOrder(), r.catalogExact(), r.catalogPhrase(),
                r.catalogFuzzy(), r.geoSport(), r.colorContainsMark(), r.pureColor());
    }

    @Test
    void isDeterministicAndVersioned() {
        assertThat(base()).isEqualTo(base());
        assertThat(base().descriptor()).startsWith(AnalysisFingerprint.FORMAT + "\n")
                .contains("pack_id=trademark-risk\n", "ruleset_version=trademark-test-1\n",
                        "ruleset_hash=" + ruleset.contentHash() + "\n", "normalization_version=normalization-1\n",
                        "matcher_version=matcher-1\n", "scoring_version=osa-similarity-1\n",
                        "external.catalog_hash=abc\n", "external.pg_major=16\n");
        assertThat(base().value()).matches("[0-9a-f]{64}");
    }

    @Test
    void externalComponentsAreSortedSoTheirOrderDoesNotMatter() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("pg_trgm_extversion", "1.6");
        reversed.put("pg_major", "16");
        reversed.put("catalog_hash", "abc");
        reversed.put("catalog_version", "catalog-1");

        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, reversed)).isEqualTo(base());
    }

    @Test
    void changingThePackIdChangesIt() {
        assertThat(AnalysisFingerprint.of(withPackId(ruleset, "other-pack"), AlgorithmVersions.CURRENT, external).value())
                .isNotEqualTo(base().value());
    }

    @Test
    void changingTheRulesetVersionChangesIt() {
        assertThat(AnalysisFingerprint.of(withVersion(ruleset, "trademark-test-2"), AlgorithmVersions.CURRENT,
                external).value()).isNotEqualTo(base().value());
    }

    @Test
    void changingTheRulesetContentChangesIt() {
        TrademarkRuleset edited = TestRulesets.variant("final_threshold: 0.80", "final_threshold: 0.81");

        assertThat(AnalysisFingerprint.of(edited, AlgorithmVersions.CURRENT, external).value())
                .isNotEqualTo(base().value());
    }

    @Test
    void changingTheNormalizationMatcherOrScoringVersionChangesIt() {
        AlgorithmVersions current = AlgorithmVersions.CURRENT;

        assertThat(AnalysisFingerprint.of(ruleset, new AlgorithmVersions("normalization-2", current.matcher(),
                current.scoring()), external).value()).isNotEqualTo(base().value());
        assertThat(AnalysisFingerprint.of(ruleset, new AlgorithmVersions(current.normalization(), "matcher-2",
                current.scoring()), external).value()).isNotEqualTo(base().value());
        assertThat(AnalysisFingerprint.of(ruleset, new AlgorithmVersions(current.normalization(), current.matcher(),
                "osa-similarity-2"), external).value()).isNotEqualTo(base().value());
    }

    @Test
    void everyExternalComponentTakesPart() {
        for (String key : external.keySet()) {
            Map<String, String> changed = new LinkedHashMap<>(external);
            changed.put(key, external.get(key) + "x");
            assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, changed).value()).as(key)
                    .isNotEqualTo(base().value());

            Map<String, String> removed = new LinkedHashMap<>(external);
            removed.remove(key);
            assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, removed).value()).as(key)
                    .isNotEqualTo(base().value());
        }
    }

    @Test
    void anAddedExternalComponentChangesItAndNoneIsAllowed() {
        Map<String, String> more = new LinkedHashMap<>(external);
        more.put("marketplace", "etsy");

        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, more).value()).isNotEqualTo(base().value());
        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, Map.of()).value())
                .isNotEqualTo(base().value());
    }

    @Test
    void rejectsAComponentThatCouldBlurTheDescriptor() {
        assertThatThrownBy(() -> AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, Map.of("Bad Key", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, Map.of("k", "a\nb=c")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theScannerBuildsItsFingerprintFromItsRulesetAndTheCurrentVersions() {
        TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex");

        assertThat(scanner.fingerprint(external)).isEqualTo(base());
    }
}
