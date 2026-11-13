package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Row;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

/**
 * The two things a seller takes away from a bulk run (ADR-029): the block that is pasted back into the Sheet and the CSV file. Both are
 * built from the rows in their input order.
 *
 * <p>The block has two columns, status and summary, tab-separated, one line per input row. No cell holds a tab or a line break, so a
 * Sheet puts each in one cell; a blank input row is a blank line, so the answer never shifts against the rows it belongs to; a table
 * that had a header gets a header line first. A cell that starts with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage
 * return gets a leading apostrophe, as {@code batch-check}'s reports do: a finding can quote a listing, and a spreadsheet would run
 * what looks like a formula.
 */
final class BulkExport {

    static final String STATUS_HEADER = "Trạng thái";
    static final String SUMMARY_HEADER = "Tóm tắt";

    private static final String[] CSV_HEADER = {"Dòng", "SKU", STATUS_HEADER, "Số phát hiện", SUMMARY_HEADER};

    private BulkExport() {
    }

    static String sheetBlock(List<Row> rows, boolean header) {
        StringJoiner lines = new StringJoiner("\n");
        if (header) {
            lines.add(STATUS_HEADER + "\t" + SUMMARY_HEADER);
        }
        for (Row row : rows) {
            boolean blank = row.status() == Status.EMPTY;
            lines.add((blank ? "" : defuse(row.status().label())) + "\t" + (blank ? "" : defuse(oneLine(row.summary()))));
        }
        return lines.toString();
    }

    /** UTF-8 with a byte order mark, so a spreadsheet reads the Vietnamese text; {@code \r\n} between lines. */
    static String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder("﻿");
        StringJoiner head = new StringJoiner(",");
        for (String name : CSV_HEADER) {
            head.add(cell(name));
        }
        out.append(head).append("\r\n");
        for (Row row : rows) {
            boolean blank = row.status() == Status.EMPTY;
            StringJoiner line = new StringJoiner(",");
            line.add(String.valueOf(row.line()));
            line.add(cell(row.sku()));
            line.add(cell(row.status().label()));
            line.add(String.valueOf(row.findings()));
            line.add(cell(blank ? "" : oneLine(row.summary())));
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    /** A link that downloads the CSV without the server keeping it. */
    static String csvHref(String csv) {
        return "data:text/csv;charset=utf-8;base64," + Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** The text on one line: every run of blanks, tabs and line breaks becomes one space. */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
    }

    /** A leading apostrophe on a cell a spreadsheet would read as a formula. */
    static String defuse(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    /** One CSV cell: defused, and quoted when it holds a comma, a quote or a line break. */
    static String cell(String value) {
        String text = defuse(value);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
