package com.thanhdatpb.listingguard.api.console.reaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.reaudit.ReauditBaselines.Baseline;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Run;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which risks of a run are new (ADR-035): a mark or a watchlist entry that the previous run of the scope did not have. The catalogs and
 * the watchlist are faked here; the real lookups are exercised by the integration test.
 */
class ReauditBaselinesTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final Instant PREVIOUS_AT = Instant.parse("2026-11-01T10:00:00Z");
    private static final String OLD = "uspto-2026-10-aaaaaaaaaaaa";
    private static final String NEW = "uspto-2026-11-bbbbbbbbbbbb";
    private static final UUID OLD_ENTRY = UUID.randomUUID();
    private static final UUID NEW_ENTRY = UUID.randomUUID();

    private static Run run(String catalog, String watchlist) {
        return new Run(UUID.randomUUID(), CLIENT, null, "ETSY", "", "etsy-listings-export", PREVIOUS_AT, catalog,
                watchlist == null ? null : 1L, watchlist, 10, 10, 3, 0, null);
    }

    /** A catalog store that knows {@code OLD} with the given marks and nothing else. */
    private static ReauditBaselines.CatalogMarks catalogs(Set<String> marksOfOld) {
        return (version, normalized) -> OLD.equals(version)
                ? Optional.of(normalized.stream().filter(marksOfOld::contains).collect(java.util.stream.Collectors.toSet()))
                : Optional.empty();
    }

    private static ReauditBaselines.EntryTimes entries(Map<UUID, Instant> updated) {
        return clientId -> updated;
    }

    private static Baseline build(Run previous, String catalog, String watchlist, Collection<String> marks,
                                  ReauditBaselines.CatalogMarks catalogs, Map<UUID, Instant> entries) {
        return new ReauditBaselines(catalogs, entries(entries)).build(CLIENT, previous, catalog, watchlist, marks);
    }

    @Test
    void withNoPreviousRunEverythingIsKnownBecauseThereIsNothingToCompareWith() {
        Baseline baseline = build(null, NEW, "w1", List.of("zorvex"), catalogs(Set.of()), Map.of());

        assertThat(baseline.first()).isTrue();
        assertThat(baseline.catalogMarkKnown().test("zorvex")).isTrue();
        assertThat(baseline.watchlistEntryKnown().test(NEW_ENTRY)).isTrue();
    }

    @Test
    void theSameCatalogVersionMakesEveryCatalogMarkKnownWithoutAskingTheCatalog() {
        ReauditBaselines.CatalogMarks neverAsked = (version, normalized) -> {
            throw new AssertionError("the catalog must not be read when the version did not change");
        };

        Baseline baseline = build(run(NEW, null), NEW, null, List.of("zorvex"), neverAsked, Map.of());

        assertThat(baseline.first()).isFalse();
        assertThat(baseline.catalogMarkKnown().test("zorvex")).isTrue();
    }

    @Test
    void whenTheCatalogVersionChangedAMarkTheOldVersionHadIsKnownAndOneItLackedIsNew() {
        Baseline baseline = build(run(OLD, null), NEW, null, List.of("zorvex", "quillmark"), catalogs(Set.of("zorvex")), Map.of());

        assertThat(baseline.catalogVersionMissing()).isFalse();
        assertThat(baseline.catalogMarkKnown().test("zorvex")).as("in both versions").isTrue();
        assertThat(baseline.catalogMarkKnown().test("quillmark")).as("only in the new version").isFalse();
    }

    @Test
    void ifThePreviousCatalogVersionIsNotStoredAnyMoreEveryCatalogRiskIsNewAndTheRunSaysWhy() {
        Baseline baseline = build(run("deleted-version", null), NEW, null, List.of("zorvex"), catalogs(Set.of("zorvex")), Map.of());

        assertThat(baseline.catalogVersionMissing()).isTrue();
        assertThat(baseline.catalogMarkKnown().test("zorvex")).isFalse();
    }

    @Test
    void ifThePreviousRunHadNoCatalogTheWholeCatalogIsNewToTheScope() {
        Baseline baseline = build(run(null, null), NEW, null, List.of("zorvex"), catalogs(Set.of("zorvex")), Map.of());

        assertThat(baseline.catalogMarkKnown().test("zorvex")).isFalse();
        assertThat(baseline.catalogVersionMissing()).isFalse();
    }

    @Test
    void aRunWithNoCatalogHasNoCatalogRiskToCompare() {
        Baseline baseline = build(run(OLD, null), null, null, List.of(), catalogs(Set.of()), Map.of());

        assertThat(baseline.catalogMarkKnown().test("anything")).isTrue();
    }

    @Test
    void theSameWatchlistVersionMakesEveryEntryKnown() {
        Baseline baseline = build(run(NEW, "w1"), NEW, "w1", List.of(), catalogs(Set.of()), Map.of());

        assertThat(baseline.watchlistEntryKnown().test(NEW_ENTRY)).isTrue();
    }

    @Test
    void whenTheWatchlistChangedAnEntryAddedOrEditedAfterThePreviousRunIsNewAndOneNotTouchedSinceIsKnown() {
        Map<UUID, Instant> updated = Map.of(OLD_ENTRY, PREVIOUS_AT.minusSeconds(3600), NEW_ENTRY, PREVIOUS_AT.plusSeconds(60));

        Baseline baseline = build(run(NEW, "w1"), NEW, "w2", List.of(), catalogs(Set.of()), updated);

        assertThat(baseline.watchlistEntryKnown().test(OLD_ENTRY)).isTrue();
        assertThat(baseline.watchlistEntryKnown().test(NEW_ENTRY)).isFalse();
        assertThat(baseline.watchlistEntryKnown().test(UUID.randomUUID())).as("an entry deleted since is never called known").isFalse();
    }

    @Test
    void anEntryEditedAtTheSecondOfThePreviousRunIsKnown() {
        Baseline baseline = build(run(NEW, "w1"), NEW, "w2", List.of(), catalogs(Set.of()), Map.of(OLD_ENTRY, PREVIOUS_AT));

        assertThat(baseline.watchlistEntryKnown().test(OLD_ENTRY)).isTrue();
    }

    @Test
    void ifThePreviousRunUsedNoWatchlistEveryEntryTouchedSinceIsNew() {
        Baseline baseline = build(run(NEW, null), NEW, "w1", List.of(), catalogs(Set.of()), Map.of(NEW_ENTRY, PREVIOUS_AT.plusSeconds(1)));

        assertThat(baseline.watchlistEntryKnown().test(NEW_ENTRY)).isFalse();
    }
}
