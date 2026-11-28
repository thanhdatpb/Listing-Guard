package com.thanhdatpb.listingguard.workspace.sheets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SheetValidationsTest {

    private static final SheetLimits LIMITS = SheetLimits.defaults();

    @Test
    void aLiteralListARangeOnAnotherSheetAndAGivenNameAreEachReadByColumn() throws IOException {
        SheetValidations.Report report = SheetValidations.read(TestWorkbooks.withValidations(), "Template", 4, LIMITS);

        assertThat(report.allowed().get(2)).containsExactlyInAnyOrder("S", "M", "L");
        assertThat(report.allowed().get(1)).containsExactlyInAnyOrder("SHIRT", "MUG", "HAT");
        assertThat(report.allowed().get(3)).containsExactlyInAnyOrder("Blue", "Red");
        assertThat(report.allowed()).doesNotContainKeys(0, 4);
        assertThat(report.listRules()).isEqualTo(3);
        assertThat(report.otherRules()).isEqualTo(1);
        assertThat(report.unresolved()).isZero();
        assertThat(report.complete()).isTrue();
        assertThat(report.empty()).isFalse();
    }

    @Test
    void aTemplateWithoutValidationsHasNothingToCompareWith() throws IOException {
        SheetValidations.Report report = SheetValidations.read(TestWorkbooks.flatFile(), "Template", 4, LIMITS);

        assertThat(report.empty()).isTrue();
        assertThat(report.listRules()).isZero();
        assertThat(report.complete()).isTrue();
    }

    @Test
    void aFormulaThatNeedsGuessingIsCountedAsNotResolvedAndNeverGuessed() throws IOException {
        SheetValidations.Report report = SheetValidations.read(TestWorkbooks.withIndirectValidation(), "Template", 2, LIMITS);

        assertThat(report.listRules()).isEqualTo(1);
        assertThat(report.unresolved()).isEqualTo(1);
        assertThat(report.allowed()).isEmpty();
        assertThat(report.complete()).isFalse();
    }

    @Test
    void aRuleInTheExtensionPartIsCountedAndNotRead() throws IOException {
        byte[] withExtension = TestWorkbooks.withExtensionRule(TestWorkbooks.withValidations());

        SheetValidations.Report report = SheetValidations.read(withExtension, "Template", 4, LIMITS);

        assertThat(report.extensionRules()).isEqualTo(1);
        assertThat(report.complete()).isFalse();
        assertThat(report.allowed().get(2)).containsExactlyInAnyOrder("S", "M", "L");
    }

    @Test
    void aTextTemplateHasNoRulesAndAnUnknownSheetIsRefused() {
        assertThat(SheetValidations.read("a\tb\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), "data", 2, LIMITS).empty()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SheetValidations.read(TestWorkbooks.withValidations(), "Nope", 4, LIMITS))
                .isInstanceOf(SheetRejected.class);
    }

    @Test
    void invalidListsTheDistinctValuesOutsideTheListAndNeverABlank() {
        assertThat(SheetValidations.invalid(Set.of("S", "M"), java.util.Arrays.asList("S", "XL", " XL ", "", null, "s", "M")))
                .containsExactly("XL", "s");
    }
}
