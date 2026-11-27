package com.thanhdatpb.listingguard.workspace.sheets;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A guess of the row that holds the field names of a flat file (ADR-059): the row of the first rows whose non-blank cells are mostly
 * written like a field name (lower case, no spaces: {@code item_sku}, {@code bullet_point[marketplace_id=...]#1.value}), with at least
 * {@value #MIN_CELLS} of them. It is a guess, never a fact: the page shows it as "guessed" and the person confirms it, and no column
 * name is written in Java. When no row qualifies the first row with two or more cells is offered, with {@code confident} false.
 *
 * @param fieldRow     the 1-based row of the field names, or 0 when the sheet has no row with two cells
 * @param firstDataRow the row after it
 * @param confident    whether the row looked like field names
 */
public record HeaderGuess(int fieldRow, int firstDataRow, boolean confident) {

    static final int MIN_CELLS = 3;
    private static final int SCAN_ROWS = 40;
    private static final double MIN_SHARE = 0.6;
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][a-z0-9_.\\-\\[\\]#=:]{0,119}");

    public static HeaderGuess of(SheetGrid grid) {
        return of(grid.rows());
    }

    public static HeaderGuess of(List<List<String>> rows) {
        int best = 0;
        int bestScore = 0;
        int firstWithCells = 0;
        for (int i = 0; i < Math.min(rows.size(), SCAN_ROWS); i++) {
            int filled = 0;
            int fieldLike = 0;
            for (String cell : rows.get(i)) {
                String text = cell == null ? "" : cell.strip();
                if (text.isEmpty()) {
                    continue;
                }
                filled++;
                if (FIELD_NAME.matcher(text).matches()) {
                    fieldLike++;
                }
            }
            if (firstWithCells == 0 && filled >= 2) {
                firstWithCells = i + 1;
            }
            if (fieldLike >= MIN_CELLS && fieldLike >= MIN_SHARE * filled && fieldLike > bestScore) {
                best = i + 1;
                bestScore = fieldLike;
            }
        }
        if (best > 0) {
            return new HeaderGuess(best, best + 1, true);
        }
        return firstWithCells > 0 ? new HeaderGuess(firstWithCells, firstWithCells + 1, false) : new HeaderGuess(0, 1, false);
    }
}
