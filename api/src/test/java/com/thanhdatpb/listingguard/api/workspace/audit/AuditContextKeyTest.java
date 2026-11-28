package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditContexts.Context;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The keys of the audit's analysis context (ADR-061, ADR-039): with the comparison of clones off they are exactly what they were before it existed, so
 * no stored audit is made again; with it on only the key of the context and the analysis version of a clone hold the pack.
 */
class AuditContextKeyTest {

    private static final Set<String> SCOPE = Set.of("title", "description");

    private static Context off() {
        return new Context("ETSY", "etsy-2026-12", "trademark-risk-2026-11b", "catalog-1", "watch-1", 3, Context.CatalogAvailability.READY);
    }

    private static Context on(String pack) {
        return new Context("ETSY", "etsy-2026-12", "trademark-risk-2026-11b", "catalog-1", "watch-1", 3, Context.CatalogAvailability.READY, pack);
    }

    @Test
    void withTheComparisonOffTheKeysAreTheOnesOfBeforeAdr039() {
        String key = AuditHasher.analysis(List.of("ctx1", "ETSY", "etsy-2026-12", "trademark-risk-2026-11b", "catalog-1", "watch-1"));
        String analysis = AuditHasher.analysis(List.of("analysis1", key, "description,title"));

        assertThat(off().cloneLeftover()).isNull();
        assertThat(off().key()).isEqualTo(key);
        assertThat(off().analysisVersion(SCOPE)).isEqualTo(analysis);
    }

    @Test
    void withTheComparisonOnTheContextKeyChangesButTheAnalysisVersionOfAListingThatIsNoCloneDoesNot() {
        Context on = on("clone-leftover-2026-12:abc");

        assertThat(on.key()).isNotEqualTo(off().key());
        assertThat(on.analysisVersion(SCOPE)).isEqualTo(off().analysisVersion(SCOPE));
    }

    @Test
    void aClonesAnalysisVersionHoldsThePackAndWhatOfTheOriginalDecidesTheResult() {
        Context on = on("clone-leftover-2026-12:abc");
        String version = on.cloneAnalysisVersion(SCOPE, "template-1:hash-1");

        assertThat(version).isNotEqualTo(on.analysisVersion(SCOPE));
        assertThat(version).isEqualTo(on.cloneAnalysisVersion(SCOPE, "template-1:hash-1"));
        assertThat(on.cloneAnalysisVersion(SCOPE, "template-1:hash-2")).isNotEqualTo(version);
        assertThat(on.cloneAnalysisVersion(SCOPE, "template-2:hash-1")).isNotEqualTo(version);
        assertThat(on.cloneAnalysisVersion(SCOPE, "none")).isNotEqualTo(version);
        assertThat(on("clone-leftover-2027-01:abc").cloneAnalysisVersion(SCOPE, "template-1:hash-1")).isNotEqualTo(version);
        assertThat(on("clone-leftover-2026-12:abd").cloneAnalysisVersion(SCOPE, "template-1:hash-1")).isNotEqualTo(version);
    }
}
