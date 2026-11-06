package com.thanhdatpb.listingguard.variation;

import static com.thanhdatpb.listingguard.variation.VariationFixtures.RULESET;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AnalysisFingerprintTest {

    private static String shippedText() throws IOException {
        try (var in = AnalysisFingerprintTest.class.getResourceAsStream("/" + VariationFixtures.RULESET_PATH)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDescriptorNamesEveryComponentThatCanChangeAFinding() {
        AnalysisFingerprint fingerprint = AnalysisFingerprint.of(RULESET, AlgorithmVersions.CURRENT, "AMAZON");

        assertThat(fingerprint.descriptor()).isEqualTo(AnalysisFingerprint.FORMAT + "\n"
                + "pack_id=variation-checks\n"
                + "ruleset_version=" + RULESET.version() + "\n"
                + "ruleset_hash=" + RULESET.contentHash() + "\n"
                + "input_version=" + AlgorithmVersions.INPUT + "\n"
                + "rules_version=" + AlgorithmVersions.RULES + "\n"
                + "marketplace=AMAZON\n");
        assertThat(fingerprint.value()).matches("[0-9a-f]{64}");
    }

    @Test
    void theSameComponentsGiveTheSameValue() {
        assertThat(AnalysisFingerprint.of(RULESET, AlgorithmVersions.CURRENT, "ETSY"))
                .isEqualTo(AnalysisFingerprint.of(RULESET, AlgorithmVersions.CURRENT, "ETSY"));
    }

    @Test
    void aDifferentMarketplaceRulesetOrAlgorithmChangesTheValue() throws IOException {
        String base = AnalysisFingerprint.of(RULESET, AlgorithmVersions.CURRENT, "AMAZON").value();

        assertThat(AnalysisFingerprint.of(RULESET, AlgorithmVersions.CURRENT, "ETSY").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(RULESET, new AlgorithmVersions("variation-input-2", AlgorithmVersions.RULES),
                "AMAZON").value()).isNotEqualTo(base);
        assertThat(AnalysisFingerprint.of(RULESET, new AlgorithmVersions(AlgorithmVersions.INPUT, "variation-rules-2"),
                "AMAZON").value()).isNotEqualTo(base);

        // a ruleset that differs by one character of a message has another content hash, hence another value
        VariationRuleset edited = VariationRulesetLoader.parse(shippedText().replace("Parent chưa khai báo variation_theme.",
                "Parent chưa khai báo variation_theme!"));
        assertThat(edited.contentHash()).isNotEqualTo(RULESET.contentHash());
        assertThat(AnalysisFingerprint.of(edited, AlgorithmVersions.CURRENT, "AMAZON").value()).isNotEqualTo(base);
    }
}
