package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import org.junit.jupiter.api.Test;

class AnalysisFingerprintTest {

    private final PersonalizationRuleset ruleset = Fixtures.ruleset();

    @Test
    void theDescriptorNamesEveryComponentInAFixedOrder() {
        AnalysisFingerprint fingerprint = AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, "etsy", "abc");

        assertThat(fingerprint.descriptor()).isEqualTo("""
                listing-guard/personalization-fingerprint/v1
                pack_id=personalization-screening
                ruleset_version=%s
                ruleset_hash=%s
                input_version=personalization-input-1
                rules_version=personalization-rules-1
                marketplace=etsy
                trademark_fingerprint=abc
                """.formatted(ruleset.version(), ruleset.contentHash()));
        assertThat(fingerprint.value()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void eachComponentChangesTheValue() {
        String base = AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, "etsy", "abc").value();

        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, "amazon", "abc").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, "etsy", "abd").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(ruleset, new AlgorithmVersions("personalization-input-2", AlgorithmVersions.RULES),
                "etsy", "abc").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(ruleset, new AlgorithmVersions(AlgorithmVersions.INPUT, "personalization-rules-2"),
                "etsy", "abc").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(Fixtures.variant("max_value_chars: 16", "max_value_chars: 17"),
                AlgorithmVersions.CURRENT, "etsy", "abc").value()).isNotEqualTo(base);
    }

    @Test
    void noCatalogHasItsOwnComponent() {
        assertThat(AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, "etsy", null).descriptor())
                .contains("trademark_fingerprint=not-configured");
    }
}
