package com.thanhdatpb.listingguard.workspace.sheets;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads tab-, comma- or semicolon-separated text (RFC 4180 quoting, quoted line breaks). The text must be UTF-8 or UTF-16 with a byte
 * order mark (what Excel's "Unicode Text" writes); any other bytes are refused rather than read as a guessed code page, because a
 * wrong guess would silently damage listing text. Cells are returned exactly as written (no trimming).
 */
final class DelimitedReader {

    static final String SHEET_NAME = "data";

    private DelimitedReader() {
    }

    /** Whether the bytes look like text at all: no NUL byte in the first 8 KiB unless the text is UTF-16 with a mark. */
    static boolean looksLikeText(byte[] bytes) {
        if (utf16(bytes) != null) {
            return true;
        }
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static java.nio.charset.Charset utf16(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    static String decode(byte[] bytes) {
        java.nio.charset.Charset charset = utf16(bytes);
        int offset = 0;
        if (charset != null) {
            offset = 2;
        } else {
            charset = StandardCharsets.UTF_8;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new SheetRejected(SheetRejected.Reason.UNSUPPORTED, "The text is not valid UTF-8 or UTF-16.", e);
        }
    }

    /** The delimiter of the text: the one of tab, comma and semicolon that most often separates cells in its first line. */
    static char delimiter(String text) {
        int tabs = 0;
        int commas = 0;
        int semicolons = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                if (tabs + commas + semicolons > 0) {
                    break;
                }
            } else if (!quoted) {
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
        return semicolons > commas ? ';' : ',';
    }

    static SheetFormat format(char delimiter) {
        return delimiter == '\t' ? SheetFormat.TSV : SheetFormat.CSV;
    }

    static SheetGrid read(byte[] bytes, SheetLimits limits, int stopAfterRows) {
        String text = decode(bytes);
        char delimiter = delimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            boolean end = i == n;
            char c = end ? '\n' : text.charAt(i);
            if (quoted) {
                if (end) {
                    throw new SheetRejected(SheetRejected.Reason.CORRUPT, "A quoted cell is never closed.");
                }
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.length() == 0 && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (end && cell.length() == 0 && row.isEmpty() && !wasQuoted) {
                    break;
                }
                row.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
                addRow(rows, row, limits);
                row = new ArrayList<>();
                if (stopAfterRows > 0 && rows.size() >= stopAfterRows) {
                    break;
                }
            } else {
                cell.append(c);
            }
        }
        return new SheetGrid(SHEET_NAME, rows);
    }

    private static void addRow(List<List<String>> rows, List<String> row, SheetLimits limits) {
        int last = row.size();
        while (last > 0 && row.get(last - 1).isEmpty()) {
            last--;
        }
        List<String> trimmed = new ArrayList<>(row.subList(0, last));
        if (last > limits.maxColumns()) {
            throw new SheetRejected(SheetRejected.Reason.TOO_MANY_COLUMNS, "A row is wider than " + limits.maxColumns() + " columns.");
        }
        if (rows.size() >= limits.maxRows()) {
            if (last > 0) {
                throw new SheetRejected(SheetRejected.Reason.TOO_MANY_ROWS, "The file has more than " + limits.maxRows() + " rows.");
            }
            return;
        }
        rows.add(trimmed);
    }
}
