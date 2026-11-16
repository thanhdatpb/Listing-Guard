package com.thanhdatpb.listingguard.api.console.reaudit;

import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Row;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What changed in the listings between two runs of one scope (ADR-035). Rows are paired by SKU: the first row with a SKU in one run
 * is the first row with that SKU in the other (the ordinal of the row says which). A row with no SKU has nothing to be paired by, so
 * it is paired only with an identical row (same hash, in the same order among the rows with that hash): an edited row without a SKU
 * is reported as one dropped and one added, never as edited. A paired row is edited when its hashes differ. A row that could not be
 * read in either run says nothing about its listing, so it is counted apart and never called edited, added or dropped when it has a
 * SKU the other run has; a row that could not be read and has no SKU cannot be paired and is only counted.
 *
 * <p>Nothing here sees a listing's text: the inputs are SKUs and hashes.
 */
public final class ReauditComparison {

    private ReauditComparison() {
    }

    /** A listing of a comparison: its SKU (null when none) and its 1-based position among the rows of its own run. */
    public record Listing(String sku, int position) {
    }

    /**
     * @param edited     SKU present in both runs with another hash; the position is the one in the current run
     * @param added      in the current run and not in the previous; the position is the one in the current run
     * @param dropped    in the previous run and not in the current; the position is the one in the previous run
     * @param unchanged  rows paired with the same hash
     * @param unreadable rows that could not be read in the current run, or in the previous one, and so were not compared
     */
    public record Changes(List<Listing> edited, List<Listing> added, List<Listing> dropped, int unchanged, int unreadable) {

        public boolean any() {
            return !edited.isEmpty() || !added.isEmpty() || !dropped.isEmpty();
        }
    }

    static Changes compare(List<Row> previous, List<Row> current) {
        Map<String, Row> before = keyed(previous);
        Map<String, Row> after = keyed(current);
        List<Listing> edited = new ArrayList<>();
        List<Listing> added = new ArrayList<>();
        List<Listing> dropped = new ArrayList<>();
        int unchanged = 0;
        int unreadable = (int) current.stream().filter(row -> row.rowHash() == null && row.sku() == null).count();
        for (Map.Entry<String, Row> entry : after.entrySet()) {
            Row now = entry.getValue();
            Row then = before.get(entry.getKey());
            if (then == null) {
                if (now.rowHash() == null) {
                    unreadable++;
                } else {
                    added.add(new Listing(now.sku(), now.position()));
                }
            } else if (now.rowHash() == null || then.rowHash() == null) {
                unreadable++;
            } else if (now.rowHash().equals(then.rowHash())) {
                unchanged++;
            } else {
                edited.add(new Listing(now.sku(), now.position()));
            }
        }
        for (Map.Entry<String, Row> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey()) && entry.getValue().rowHash() != null) {
                Row then = entry.getValue();
                dropped.add(new Listing(then.sku(), then.position()));
            }
        }
        return new Changes(List.copyOf(edited), List.copyOf(added), List.copyOf(dropped), unchanged, unreadable);
    }

    /** The rows by identity, in file order. A row that cannot be identified (no SKU and no hash) is left out. */
    private static Map<String, Row> keyed(List<Row> rows) {
        Map<String, Row> keyed = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Row row : rows) {
            if (row.sku() != null) {
                keyed.put("sku:" + row.sku() + "#" + row.ordinal(), row);
            } else if (row.rowHash() != null) {
                int occurrence = seen.merge(row.rowHash(), 1, Integer::sum);
                keyed.put("hash:" + row.rowHash() + "#" + occurrence, row);
            }
        }
        return keyed;
    }
}
