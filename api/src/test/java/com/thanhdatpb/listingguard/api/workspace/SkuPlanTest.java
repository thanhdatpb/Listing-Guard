package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.SkuPlan.Mode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The SKUs of a clone made from a pattern or typed (ADR-062 §4). Pure text: nothing here knows an account. */
class SkuPlanTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void aPatternKeepsTheOldSkuUntilTheNumberIsAsked() {
        assertThat(SkuPlan.same().apply(id, "ABC-1", 0)).isEqualTo("ABC-1");
    }

    @Test
    void prefixSuffixAndReplacementAreAppliedInThatOrder() {
        SkuPlan plan = new SkuPlan(Mode.PATTERN, "NEW-", "-C", "OLD", "FRESH", null, 0, Map.of());

        assertThat(plan.apply(id, "OLD-SHIRT", 0)).isEqualTo("NEW-FRESH-SHIRT-C");
    }

    @Test
    void theReplacementIsAPlainStringNotARegularExpression() {
        SkuPlan plan = new SkuPlan(Mode.PATTERN, "", "", ".*", "X", null, 0, Map.of());

        assertThat(plan.apply(id, "A.*B", 0)).isEqualTo("AXB");
        assertThat(plan.apply(id, "AAB", 0)).isEqualTo("AAB");
    }

    @Test
    void theNumberRunsFromTheFirstNumberAndIsPaddedWithZeros() {
        SkuPlan plan = new SkuPlan(Mode.PATTERN, "", "-C", "", "", 7, 3, Map.of());

        assertThat(plan.apply(id, "ABC", 0)).isEqualTo("ABC-C007");
        assertThat(plan.apply(id, "ABC", 2)).isEqualTo("ABC-C009");
        assertThat(new SkuPlan(Mode.PATTERN, "", "", "", "", 1, 0, Map.of()).apply(id, "ABC", 11)).isEqualTo("ABC12");
        assertThat(new SkuPlan(Mode.PATTERN, "", "", "", "", 1, 2, Map.of()).apply(id, "ABC", 99)).isEqualTo("ABC100");
    }

    @Test
    void aTypedSkuIsTheTypedTextWithoutItsOuterBlanksAndAnUntypedOneIsEmpty() {
        SkuPlan plan = new SkuPlan(Mode.MANUAL, "ignored", "ignored", "", "", null, 0, Map.of(id, "  MINE-1 "));

        assertThat(plan.apply(id, "OLD", 0)).isEqualTo("MINE-1");
        assertThat(plan.apply(UUID.randomUUID(), "OLD", 1)).isEmpty();
    }

    @Test
    void theWidthIsBoundedSoAMistakeCannotMakeAHugeString() {
        SkuPlan plan = new SkuPlan(Mode.PATTERN, "", "", "", "", 1, 1_000_000, Map.of());

        assertThat(plan.apply(id, "A", 0).length()).isLessThanOrEqualTo(14);
    }
}
