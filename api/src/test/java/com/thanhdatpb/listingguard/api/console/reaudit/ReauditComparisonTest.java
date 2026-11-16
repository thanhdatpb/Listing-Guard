package com.thanhdatpb.listingguard.api.console.reaudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.reaudit.ReauditComparison.Changes;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What changed between two runs of one scope (ADR-035): paired by SKU, compared by hash, never by text. */
class ReauditComparisonTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    private static Row row(int position, String sku, int ordinal, String hash) {
        return new Row(position, sku, ordinal, hash, hash == null ? ReauditStore.UNREADABLE : ReauditStore.CHECKED, 0);
    }

    private static List<String> skus(List<ReauditComparison.Listing> listings) {
        return listings.stream().map(ReauditComparison.Listing::sku).toList();
    }

    @Test
    void anEditedListingIsTheSameSkuWithAnotherHash() {
        Changes changes = ReauditComparison.compare(List.of(row(1, "E-1", 1, A), row(2, "E-2", 1, B)),
                List.of(row(1, "E-1", 1, A), row(2, "E-2", 1, C)));

        assertThat(skus(changes.edited())).containsExactly("E-2");
        assertThat(changes.edited().get(0).position()).isEqualTo(2);
        assertThat(changes.unchanged()).isEqualTo(1);
        assertThat(changes.added()).isEmpty();
        assertThat(changes.dropped()).isEmpty();
        assertThat(changes.any()).isTrue();
    }

    @Test
    void aSkuOnlyInTheNewRunIsNewAndOneOnlyInTheOldRunIsDropped() {
        Changes changes = ReauditComparison.compare(List.of(row(1, "E-1", 1, A), row(2, "E-2", 1, B)),
                List.of(row(1, "E-2", 1, B), row(2, "E-3", 1, C)));

        assertThat(skus(changes.added())).containsExactly("E-3");
        assertThat(skus(changes.dropped())).containsExactly("E-1");
        assertThat(changes.dropped().get(0).position()).as("the position a dropped row had in the previous run").isEqualTo(1);
        assertThat(changes.edited()).isEmpty();
        assertThat(changes.unchanged()).isEqualTo(1);
    }

    @Test
    void theSameFileTwiceHasNoChange() {
        List<Row> rows = List.of(row(1, "E-1", 1, A), row(2, null, 0, B));

        Changes changes = ReauditComparison.compare(rows, rows);

        assertThat(changes.any()).isFalse();
        assertThat(changes.unchanged()).isEqualTo(2);
    }

    @Test
    void aSkuThatRepeatsInTheFileIsPairedByItsOrderAmongTheRowsWithThatSku() {
        Changes changes = ReauditComparison.compare(List.of(row(1, "V-1", 1, A), row(2, "V-1", 2, B)),
                List.of(row(1, "V-1", 1, A), row(2, "V-1", 2, C)));

        assertThat(changes.edited()).hasSize(1);
        assertThat(changes.edited().get(0).position()).isEqualTo(2);
        assertThat(changes.unchanged()).isEqualTo(1);
    }

    @Test
    void aRowWithoutASkuIsPairedOnlyWithAnIdenticalRowSoAnEditShowsAsDroppedAndNew() {
        Changes changes = ReauditComparison.compare(List.of(row(1, null, 0, A), row(2, null, 0, B)),
                List.of(row(1, null, 0, A), row(2, null, 0, C)));

        assertThat(changes.edited()).isEmpty();
        assertThat(changes.unchanged()).isEqualTo(1);
        assertThat(changes.added()).hasSize(1);
        assertThat(changes.added().get(0).position()).isEqualTo(2);
        assertThat(changes.dropped()).hasSize(1);
    }

    @Test
    void twoIdenticalRowsWithoutASkuAreTwoRowsNotOne() {
        Changes changes = ReauditComparison.compare(List.of(row(1, null, 0, A), row(2, null, 0, A)), List.of(row(1, null, 0, A)));

        assertThat(changes.unchanged()).isEqualTo(1);
        assertThat(changes.dropped()).hasSize(1);
    }

    @Test
    void aRowThatCouldNotBeReadIsCountedApartAndNeverCalledEditedNewOrDropped() {
        Changes changes = ReauditComparison.compare(
                List.of(row(1, "E-1", 1, A), row(2, "E-2", 1, null), row(3, "E-3", 1, C)),
                List.of(row(1, "E-1", 1, null), row(2, "E-2", 1, B), row(3, "E-9", 1, null), row(4, null, 0, null)));

        assertThat(changes.edited()).isEmpty();
        assertThat(changes.unchanged()).isZero();
        assertThat(changes.unreadable()).as("E-1 now, E-2 before, E-9 and the one without a SKU").isEqualTo(4);
        assertThat(changes.added()).isEmpty();
        assertThat(skus(changes.dropped())).as("E-3 is really gone").containsExactly("E-3");
    }

    @Test
    void anEmptyPreviousRunMakesEveryRowNew() {
        Changes changes = ReauditComparison.compare(List.of(), List.of(row(1, "E-1", 1, A)));

        assertThat(skus(changes.added())).containsExactly("E-1");
    }
}
