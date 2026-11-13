package com.thanhdatpb.listingguard.api.console.bulk;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads delimited text, the way a spreadsheet writes it, into rows of cells (ADR-029). It exists because the bulk check must keep a
 * blank row where it is: the answer is pasted back beside the rows it belongs to, so a reader that skips blank lines would shift
 * every answer after the gap.
 *
 * <p>A cell may be wrapped in double quotes; inside, the delimiter and line breaks are text and {@code ""} is one quote (this is how
 * Google Sheets writes a cell with a line break or a tab). A quote in the middle of an unquoted cell is an ordinary character. A line
 * break outside quotes ends a row ({@code \r\n}, {@code \n} or {@code \r}); one inside a quoted cell is kept as {@code \n}. A byte
 * order mark at the start is dropped. A blank line in the middle is a row with one empty cell; blank rows at the end are dropped (a
 * Sheet's copy ends with a line break, and nothing after the last row can be pasted back against anything).
 */
final class DelimitedText {

    /** One row: the line of the text where it starts (the first line is 1) and its cells, as written. */
    record Row(int line, List<String> cells) {

        boolean blank() {
            return cells.stream().allMatch(String::isBlank);
        }
    }

    record Table(char delimiter, List<Row> rows) {
    }

    /** A quote that is never closed: the text after it cannot be told apart from the cell. */
    static final class FormatException extends RuntimeException {

        final int line;

        FormatException(int line) {
            super("quote opened at line " + line + " is never closed", null, false, false);
            this.line = line;
        }
    }

    private DelimitedText() {
    }

    /** Tab, comma or semicolon, whichever the first row (outside quotes) holds most of; tab when it holds none. */
    static char detectDelimiter(String text) {
        String s = withoutByteOrderMark(text);
        int tabs = 0;
        int commas = 0;
        int semicolons = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '\n' || c == '\r') {
                    break;
                }
                if (c == '\t') {
                    tabs++;
                } else if (c == ',') {
                    commas++;
                } else if (c == ';') {
                    semicolons++;
                }
            }
        }
        if (tabs > 0 && tabs >= commas && tabs >= semicolons) {
            return '\t';
        }
        if (commas > 0 && commas >= semicolons) {
            return ',';
        }
        return semicolons > 0 ? ';' : '\t';
    }

    /** @throws FormatException if a quoted cell is never closed */
    static Table parse(String text, char delimiter) {
        String s = withoutByteOrderMark(text);
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellStart = true;
        boolean rowStarted = false;
        int line = 1;
        int rowLine = 1;
        int quoteLine = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                    } else {
                        quoted = false;
                        i++;
                    }
                } else if (c == '\r') {
                    if (i + 1 < n && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    cell.append('\n');
                    line++;
                    i++;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"' && cellStart) {
                quoted = true;
                quoteLine = line;
                cellStart = false;
                rowStarted = true;
                i++;
            } else if (c == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
                cellStart = true;
                rowStarted = true;
                i++;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                rows.add(new Row(rowLine, List.copyOf(cells)));
                cells.clear();
                cell.setLength(0);
                cellStart = true;
                rowStarted = false;
                line++;
                rowLine = line;
                i++;
            } else {
                cell.append(c);
                cellStart = false;
                rowStarted = true;
                i++;
            }
        }
        if (quoted) {
            throw new FormatException(quoteLine);
        }
        if (rowStarted) {
            cells.add(cell.toString());
            rows.add(new Row(rowLine, List.copyOf(cells)));
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).blank()) {
            rows.remove(rows.size() - 1);
        }
        return new Table(delimiter, List.copyOf(rows));
    }

    private static String withoutByteOrderMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
