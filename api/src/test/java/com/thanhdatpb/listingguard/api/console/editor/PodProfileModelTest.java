package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The invariants of an imported POD profile (ADR-048). Values are invented. */
class PodProfileModelTest {

    private static final List<EditorMarketplace> BOTH = List.of(EditorMarketplace.ETSY, EditorMarketplace.AMAZON);

    private static PodProfiles.PrintArea verified(String variant) {
        return new PodProfiles.PrintArea(variant, PodProfiles.Verification.VERIFIED, "BLK-01", BigDecimal.TEN, BigDecimal.TEN,
                null, "Operator A", "2026-11-20");
    }

    @Test
    void aPrintAreaIsVerifiedOnlyWithASourceAndTwoPositiveDimensions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea("S", PodProfiles.Verification.VERIFIED,
                " ", BigDecimal.TEN, BigDecimal.TEN, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea("S", PodProfiles.Verification.VERIFIED,
                "BLK-01", BigDecimal.TEN, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea("S", PodProfiles.Verification.VERIFIED,
                "BLK-01", BigDecimal.TEN, BigDecimal.valueOf(-1), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea("S", PodProfiles.Verification.VERIFIED,
                "BLK-01", BigDecimal.ZERO, BigDecimal.TEN, null, null, null));
        assertThat(new PodProfiles.PrintArea("S", PodProfiles.Verification.UNVERIFIED, null, null, null, null, null, null)
                .verified()).isFalse();
        assertThat(verified(" s ").variant()).isEqualTo("s");
    }

    @Test
    void aCharacterLimitMustBePositiveAndADateMustBeIso() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea(null,
                PodProfiles.Verification.UNVERIFIED, null, null, null, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.PrintArea(null,
                PodProfiles.Verification.UNVERIFIED, null, null, null, null, null, "20/11/2026"));
    }

    @Test
    void aProfileHasOneAreaOrAListOfThemWithOneAreaPerVariant() {
        var one = new PodProfiles.PrintArea(PodProfiles.Verification.UNVERIFIED, null, null, null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodProfiles.Profile("sample-tee", 1, BOTH, List.of("sku"), null, List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PodProfiles.Profile("sample-tee", 1, BOTH, List.of("sku"), one, List.of(verified("S")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.Profile("sample-tee", 1, BOTH,
                List.of("sku", "size"), null, List.of(verified("S"), verified(" s")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new PodProfiles.Profile("Sample Tee", 1, BOTH, List.of("sku"),
                one, List.of()));
        assertThat(new PodProfiles.Profile("mug-11oz", 1, BOTH, List.of("sku"), one, List.of()).key()).isEqualTo("mug-11oz-v1");
    }

    @Test
    void theAreaForASizeIsTheNamedOneThenTheWholeProductOneThenNone() {
        var s = verified("S");
        var all = verified(null);
        var named = new PodProfiles.Profile("sample-tee", 1, BOTH, List.of("sku", "size"), null, List.of(s), List.of());
        var withFallback = new PodProfiles.Profile("sample-tee", 1, BOTH, List.of("sku", "size"), null, List.of(s, all), List.of());

        assertThat(named.areaFor("S")).isSameAs(s);
        assertThat(named.areaFor("M")).isNull();
        assertThat(named.areaFor("")).isNull();
        assertThat(withFallback.areaFor("s")).isSameAs(s);
        assertThat(withFallback.areaFor("M")).isSameAs(all);
        assertThat(withFallback.verified()).isTrue();
        assertThat(new PodProfiles.Profile("sample-tee", 1, BOTH, List.of("sku"), null,
                List.of(new PodProfiles.PrintArea(null, PodProfiles.Verification.UNVERIFIED, null, null, null, null, null, null)),
                List.of()).verified()).isFalse();
    }
}
