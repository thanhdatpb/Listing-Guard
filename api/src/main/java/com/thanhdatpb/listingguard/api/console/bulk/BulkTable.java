package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A pasted or uploaded table, read and bounded (ADR-029): the cells of every row, the label of every column and where the data
 * starts. It holds no marketplace limit and checks nothing; it decides only what is a header, what a column is called and whether the
 * table fits the row limit.
 */
final class BulkTable {

    /** Whether the first row is a header: decided from the row, or by the seller. */
    enum HeaderMode {
        AUTO, YES, NO;

        static HeaderMode parse(String value) {
            if (value == null) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "yes" -> YES;
                case "no" -> NO;
                default -> AUTO;
            };
        }
    }

    /** An input the page refuses, with the HTTP status of the refusal and a message for the seller. */
    static final class Refused extends RuntimeException {

        final int status;

        Refused(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    /**
     * @param header whether the first row was taken as a header
     * @param labels the label of every column: its header text, or {@code Cột A}, {@code Cột B}... when there is none or it is
     *               blank; a repeated one gets {@code (2)}, {@code (3)}
     * @param rows   the data rows in order, blank ones included (a blank row keeps its place so the answer lines up with the Sheet)
     */
    record Prepared(char delimiter, boolean header, List<String> labels, List<DelimitedText.Row> rows) {

        int columns() {
            return labels.size();
        }
    }

    private static final int LABEL_MAX_CHARS = 120;

    private BulkTable() {
    }

    /**
     * @param remembered the column labels the account has a mapping for, so a header made of them is recognized even when no name
     *                   is in the list
     * @throws Refused when the text cannot be read, holds no data row, or holds more than {@code maxRows}
     */
    static Prepared prepare(String text, char delimiter, HeaderMode mode, EditorMarketplace marketplace, Set<String> remembered,
                            int maxRows) {
        DelimitedText.Table table;
        try {
            table = DelimitedText.parse(text, delimiter);
        } catch (DelimitedText.FormatException e) {
            throw new Refused(400, "Dữ liệu không đọc được: dấu nháy kép mở ở dòng " + e.line
                    + " không được đóng. Kiểm tra ô đó trong Sheet rồi chép lại.");
        }
        List<DelimitedText.Row> all = table.rows();
        if (all.isEmpty()) {
            throw new Refused(400, "Chưa có dữ liệu. Dán các dòng đã chép từ Google Sheet, hoặc chọn một file.");
        }
        int columns = all.stream().mapToInt(row -> row.cells().size()).max().orElse(1);
        boolean header = switch (mode) {
            case YES -> true;
            case NO -> false;
            case AUTO -> looksLikeHeader(all.get(0), marketplace, remembered);
        };
        List<DelimitedText.Row> rows = header ? all.subList(1, all.size()) : all;
        if (rows.isEmpty()) {
            throw new Refused(400, "Chỉ có dòng tiêu đề, chưa có dòng dữ liệu nào.");
        }
        if (rows.size() > maxRows) {
            throw new Refused(400, "Có " + rows.size() + " dòng dữ liệu, vượt giới hạn " + maxRows
                    + " dòng của một lần kiểm tra. Chia bảng thành nhiều lần dán (mỗi lần tối đa " + maxRows + " dòng).");
        }
        return new Prepared(delimiter, header, labels(header ? all.get(0).cells() : List.of(), columns), List.copyOf(rows));
    }

    private static boolean looksLikeHeader(DelimitedText.Row first, EditorMarketplace marketplace, Set<String> remembered) {
        for (String cell : first.cells()) {
            String label = cell.trim();
            if (label.isEmpty()) {
                continue;
            }
            if (remembered.contains(label) || BulkTargets.guess(marketplace, label).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> labels(List<String> headerCells, int columns) {
        List<String> labels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns; i++) {
            String raw = i < headerCells.size() ? headerCells.get(i).trim() : "";
            String base = raw.isEmpty() ? "Cột " + letters(i) : clip(raw);
            String label = base;
            int repeat = 2;
            while (!seen.add(label)) {
                label = base + " (" + repeat++ + ")";
            }
            labels.add(label);
        }
        return List.copyOf(labels);
    }

    private static String clip(String text) {
        return text.codePointCount(0, text.length()) <= LABEL_MAX_CHARS ? text
                : text.substring(0, text.offsetByCodePoints(0, LABEL_MAX_CHARS));
    }

    /** The name a spreadsheet gives column {@code index} (0 is A, 25 is Z, 26 is AA). */
    static String letters(int index) {
        StringBuilder out = new StringBuilder();
        int n = index;
        do {
            out.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return out.toString();
    }
}
