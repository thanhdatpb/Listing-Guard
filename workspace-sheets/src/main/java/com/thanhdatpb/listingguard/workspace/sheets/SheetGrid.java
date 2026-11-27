package com.thanhdatpb.listingguard.workspace.sheets;

import java.util.List;

/**
 * The cells of one sheet as text. {@code rows.get(0)} is row 1 of the sheet; a row with no content is an empty list, so the index of
 * a row is its row number minus one and a row number the seller reads in Excel is the one the workspace shows. A cell is the value the
 * file stored (a formula's last saved result, never a recalculation), formatted the way Excel shows it; a blank cell is {@code ""}.
 * Trailing blank cells are dropped.
 */
public record SheetGrid(String sheetName, List<List<String>> rows) {

    public SheetGrid {
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** The cells of a row (1-based), or an empty list beyond the last row. */
    public List<String> row(int rowNumber) {
        return rowNumber >= 1 && rowNumber <= rows.size() ? rows.get(rowNumber - 1) : List.of();
    }

    SheetGrid withName(String name) {
        return new SheetGrid(name, rows);
    }

    public int rowCount() {
        return rows.size();
    }

    public int width() {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }
}
