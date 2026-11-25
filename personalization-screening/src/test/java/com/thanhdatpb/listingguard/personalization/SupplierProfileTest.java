package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An imported POD profile (ADR-048) changes the length limit and the analysis fingerprint, and nothing else. */
class SupplierProfileTest {

    private static final String SHA = "a".repeat(64);

    private final PersonalizationRuleset ruleset = Fixtures.ruleset();
    private final PersonalizationScreener screener = new PersonalizationScreener(ruleset);

    private static PersonalizationInput input(String label, String text) {
        return new PersonalizationInput(null, null, List.of(new PersonalizationInput.Value(label, text)));
    }

    @Test
    void anInvalidIdentityOrLimitIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SupplierProfile("T-Shirt", 1, SHA, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new SupplierProfile("tee", 0, SHA, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new SupplierProfile("tee", 1, "abc", 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new SupplierProfile("tee", 1, SHA, 0));
        assertThat(new SupplierProfile("mug-11oz", 3, SHA, 12).key()).isEqualTo("mug-11oz-v3");
    }

    @Test
    void theSupplierLimitReplacesTheRulesetLimitAndNamesItselfInTheProfileCode() {
        String text = "ABCDEFGH";
        assertThat(screener.screen(input("name", text), null).findings()).isEmpty();

        ScreeningResult result = screener.screen(input("name", text), null, new SupplierProfile("tee", 2, SHA, 5));

        assertThat(result.printProfile()).isEqualTo("latin_basic+tee-v2");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo("too_long");
            assertThat(finding.message()).contains("5").contains("latin_basic+tee-v2");
        });
        assertThat(screener.screen(input("name", text), null, new SupplierProfile("tee", 2, SHA, 8)).findings()).isEmpty();
    }

    @Test
    void theSupplierLimitDropsThePlaceholderLimitOfALabel() {
        assertThat(screener.screen(input("number", "1234"), null).findings())
                .extracting(TextFinding::kind).containsExactly("too_long");

        assertThat(screener.screen(input("number", "1234"), null, new SupplierProfile("tee", 1, SHA, 10)).findings())
                .isEmpty();
    }

    @Test
    void theCharactersStayTheRulesetsAndANullProfileChangesNothing() {
        ScreeningResult withProfile = screener.screen(input("name", "Đạt"), null, new SupplierProfile("tee", 1, SHA, 10));
        ScreeningResult without = screener.screen(input("name", "Đạt"), null);

        assertThat(withProfile.findings()).extracting(TextFinding::kind).containsExactly("character_not_allowed");
        assertThat(screener.screen(input("name", "Đạt"), null, null)).isEqualTo(without);
    }

    @Test
    void theFingerprintIsUnchangedWithoutAProfileAndNamesIdVersionHashAndLimitWithOne() {
        AnalysisFingerprint plain = screener.fingerprint("etsy", "abc");
        assertThat(screener.fingerprint("etsy", "abc", null)).isEqualTo(plain);
        assertThat(plain.descriptor()).doesNotContain("supplier_");

        SupplierProfile profile = new SupplierProfile("tee", 2, SHA, 5);
        AnalysisFingerprint with = screener.fingerprint("etsy", "abc", profile);
        assertThat(with.descriptor()).endsWith("supplier_profile=tee-v2:" + SHA + "\nsupplier_max_chars=5\n");
        assertThat(with.value()).isNotEqualTo(plain.value());
    }

    @Test
    void anotherIdVersionHashOrLimitIsAnotherAnalysisVersion() {
        String base = screener.fingerprint("etsy", "abc", new SupplierProfile("tee", 2, SHA, 5)).value();

        assertThat(screener.fingerprint("etsy", "abc", new SupplierProfile("mug", 2, SHA, 5)).value()).isNotEqualTo(base);
        assertThat(screener.fingerprint("etsy", "abc", new SupplierProfile("tee", 3, SHA, 5)).value()).isNotEqualTo(base);
        assertThat(screener.fingerprint("etsy", "abc", new SupplierProfile("tee", 2, "b".repeat(64), 5)).value())
                .isNotEqualTo(base);
        assertThat(screener.fingerprint("etsy", "abc", new SupplierProfile("tee", 2, SHA, 6)).value()).isNotEqualTo(base);
        assertThat(screener.fingerprint("etsy", "abc", new SupplierProfile("tee", 2, SHA, 5)).value()).isEqualTo(base);
    }
}
