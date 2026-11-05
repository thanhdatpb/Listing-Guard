package com.thanhdatpb.listingguard.core.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FindingTest {

    private static final FixSuggestion FIX = FixSuggestion.replace("Cắt tiêu đề.", "Handmade mug");

    @Test
    void theSevenArgumentFormHasNoFix() {
        Finding finding = new Finding("title_length", Severity.ERROR, "title", "v", "m", "s", "etsy-2026-11");

        assertThat(finding.fix()).isNull();
        assertThat(finding.ruleId()).isEqualTo("title_length");
        assertThat(finding.rulesetVersion()).isEqualTo("etsy-2026-11");
    }

    @Test
    void aFindingWithoutAFixEqualsTheSameFindingBuiltTheOldWay() {
        Finding old = new Finding("r", Severity.INFO, "f", null, "m", "s", "v1");
        Finding explicit = new Finding("r", Severity.INFO, "f", null, "m", "s", "v1", null);

        assertThat(explicit).isEqualTo(old);
    }

    @Test
    void withoutFixDropsOnlyTheFix() {
        Finding withFix = new Finding("r", Severity.ERROR, "title", "v", "m", "s", "v1", FIX);

        Finding stripped = withFix.withoutFix();

        assertThat(stripped.fix()).isNull();
        assertThat(stripped).isEqualTo(new Finding("r", Severity.ERROR, "title", "v", "m", "s", "v1"));
        assertThat(withFix.fix()).isEqualTo(FIX);
    }

    @Test
    void withoutFixOnAFindingWithoutOneReturnsTheSameInstance() {
        Finding finding = new Finding("r", Severity.INFO, "f", null, "m", "s", "v1");

        assertThat(finding.withoutFix()).isSameAs(finding);
    }
}
