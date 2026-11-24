package com.thanhdatpb.listingguard.api.policymonitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyImpactTest {
    private static final String OLD = "a".repeat(64);
    private static final String NEW = "b".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Test
    void classifiesMovesOnlyForUnambiguousIdenticalHashes() {
        var sections = PolicyImpact.sections(List.of(
                new PolicyDiffEngine.SectionChange("SECTION_REMOVED", "Old path", OLD, null, null),
                new PolicyDiffEngine.SectionChange("SECTION_ADDED", "New path", null, OLD, null),
                new PolicyDiffEngine.SectionChange("SECTION_CHANGED", "Edited", OLD, NEW, null),
                new PolicyDiffEngine.SectionChange("SECTION_ADDED", "Added", null, NEW, null),
                new PolicyDiffEngine.SectionChange("SECTION_REMOVED", "Removed", "c".repeat(64), null, null)));
        assertThat(sections).extracting(PolicyImpact.Section::type)
                .containsExactly("SECTION_MOVED", "SECTION_CHANGED", "SECTION_ADDED", "SECTION_REMOVED");
        assertThat(sections.getFirst()).isEqualTo(new PolicyImpact.Section("SECTION_MOVED", "Old path", "New path", OLD, OLD));
        assertThat(PolicyImpact.sections(List.of())).isEmpty();
    }

    @Test
    void requiresApprovedSourceExactHashesVersionsAndEffectiveDate() {
        var change = change("2026-11-12", "https://www.etsy.com/legal/sellers/");
        var approval = new PolicyImpact.Approval(OLD, NEW, DAY);
        assertThat(PolicyImpact.reason(change, approval, false)).isEqualTo(PolicyImpact.Reason.NONE);
        assertThat(PolicyImpact.reason(change, null, false)).isEqualTo(PolicyImpact.Reason.SOURCE_NOT_APPROVED);
        assertThat(PolicyImpact.reason(change("2026-11-12", "javascript:invalid"), approval, false))
                .isEqualTo(PolicyImpact.Reason.SOURCE_NOT_APPROVED);
        assertThat(PolicyImpact.reason(change, new PolicyImpact.Approval(OLD, OLD, DAY), false))
                .isEqualTo(PolicyImpact.Reason.HASH_MISMATCH);
        assertThat(PolicyImpact.reason(change, new PolicyImpact.Approval(OLD, NEW, null), false))
                .isEqualTo(PolicyImpact.Reason.EFFECTIVE_DATE_MISSING);
        assertThat(PolicyImpact.reason(change("not-a-version", change.url()), approval, false))
                .isEqualTo(PolicyImpact.Reason.INVALID_VERSION);
        assertThat(PolicyImpact.reason(change, approval, true)).isEqualTo(PolicyImpact.Reason.SOURCE_ALERT);
    }

    private static PolicyDiffEngine.DocumentChange change(String version, String url) {
        return new PolicyDiffEngine.DocumentChange("VERSION_CHANGED", "ETSY", url, "Sample policy",
                "2026-11-10", version, OLD, NEW, true, List.of());
    }
}
