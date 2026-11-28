package com.thanhdatpb.listingguard.workspace.sheets;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The writing side of the workspace's file handling (ADR-063). No Spring, no database, no network. It never opens the macro project of an
 * {@code .xlsm}: the workbook is read and written by POI's package model, which carries a part it does not know (the {@code vbaProject}) as
 * the bytes it found. It works under the same limits as {@link SheetReader} and takes the same lock, because POI's zip-bomb settings are
 * process-wide. A message never holds a cell value or a file name.
 */
public final class SheetWriter {

    /** The most characters one Excel cell holds. */
    public static final int MAX_CELL_CHARS = 32_767;

    private static final Pattern BREAKS = Pattern.compile("\r\n|[\r\n\u0085\u2028\u2029]|\t");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final String LINE_END = "\r\n";

    private SheetWriter() {
    }

    /** A text file and how many of its cells had a tab or a line break replaced by a space. */
    public record TextFile(byte[] bytes, int replacedCells, int rows) {
    }

    // ------------------------------------------------------------------ a workbook

    /**
     * A copy of {@code template} (an {@code .xlsx} or {@code .xlsm}) whose sheet {@code sheetName} has every row from {@code firstDataRow}
     * (1-based) down replaced by {@code rows}. The rows above, the other sheets, the data validations, the defined names, the formats of the
     * cells that stay and the macro project are not touched by this method. A written cell takes the format of the template's cell in the same
     * column of its first data row, when there was one.
     *
     * @param rows the cells of each row, from column A; a row may be shorter than the others
     */
    public static byte[] workbook(byte[] template, String sheetName, int firstDataRow, List<List<SheetCell>> rows, SheetLimits limits) {
        if (firstDataRow < 1) {
            throw new IllegalArgumentException("firstDataRow is 1-based.");
        }
        SheetFormat format = SheetReader.detect(template, limits);
        if (!format.isExcel()) {
            throw new SheetRejected(Reason.UNSUPPORTED, "The template is not a workbook.");
        }
        if ((long) firstDataRow - 1 + rows.size() > limits.maxRows()) {
            throw new SheetRejected(Reason.TOO_MANY_ROWS, "The sheet would have content below row " + limits.maxRows() + ".");
        }
        for (List<SheetCell> row : rows) {
            if (row.size() > limits.maxColumns()) {
                throw new SheetRejected(Reason.TOO_MANY_COLUMNS, "A row is wider than " + limits.maxColumns() + " columns.");
            }
        }
        synchronized (XlsxReader.LOCK) {
            XlsxReader.configure(limits);
            try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(template))) {
                XSSFSheet sheet = book.getSheet(sheetName);
                if (sheet == null) {
                    throw new SheetRejected(Reason.SHEET_NOT_FOUND, "The workbook has no sheet with this name.");
                }
                int first = firstDataRow - 1;
                Map<Integer, CellStyle> styles = new HashMap<>();
                Row sample = sheet.getRow(first);
                if (sample != null) {
                    sample.forEach(cell -> {
                        if (cell.getCellStyle() != null && cell.getCellStyle().getIndex() != 0) {
                            styles.put(cell.getColumnIndex(), cell.getCellStyle());
                        }
                    });
                }
                List<Row> old = new ArrayList<>();
                sheet.rowIterator().forEachRemaining(r -> {
                    if (r.getRowNum() >= first) {
                        old.add(r);
                    }
                });
                for (Row row : old) {
                    sheet.removeRow(row);
                }
                for (int i = 0; i < rows.size(); i++) {
                    Row row = null;
                    List<SheetCell> cells = rows.get(i);
                    for (int c = 0; c < cells.size(); c++) {
                        SheetCell value = cells.get(c);
                        if (value == null || value.isBlank()) {
                            continue;
                        }
                        if (row == null) {
                            row = sheet.createRow(first + i);
                        }
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                        CellStyle style = styles.get(c);
                        if (style != null) {
                            cell.setCellStyle(style);
                        }
                        if (value.isNumber()) {
                            cell.setCellValue(value.number().doubleValue());
                        } else {
                            if (value.text().length() > MAX_CELL_CHARS) {
                                throw new SheetRejected(Reason.CELL_TOO_LONG, "A cell is longer than " + MAX_CELL_CHARS + " characters.");
                            }
                            cell.setCellValue(value.text());
                        }
                    }
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                book.write(out);
                if (out.size() > limits.maxBytes()) {
                    throw new SheetRejected(Reason.TOO_LARGE, "The file is larger than " + limits.maxBytes() + " bytes.");
                }
                return out.toByteArray();
            } catch (SheetRejected e) {
                throw e;
            } catch (Exception | StackOverflowError e) {
                throw XlsxReader.map(e);
            }
        }
    }

    // ------------------------------------------------------------------ a tab-delimited file

    /**
     * A tab-delimited text file: the rows above the data verbatim ({@code headerRows}), then one line per row of {@code rows}; every line has
     * {@code width} fields (a shorter row is padded with empty fields), separated by a tab and ended by CR LF. A number is written in its
     * canonical form. A tab or a line break inside a cell is replaced by one space, and the cells that had one are counted; a character the
     * encoding cannot write refuses the whole file instead of being changed to a question mark.
     */
    public static TextFile delimited(List<List<String>> headerRows, int width, List<List<SheetCell>> rows, TextEncoding encoding) {
        int[] replaced = {0};
        StringBuilder text = new StringBuilder();
        for (List<String> header : headerRows) {
            List<String> cells = new ArrayList<>();
            for (String cell : header) {
                cells.add(plain(cell == null ? "" : cell, replaced));
            }
            line(text, cells, width);
        }
        for (List<SheetCell> row : rows) {
            List<String> cells = new ArrayList<>(row.size());
            for (SheetCell cell : row) {
                cells.add(cell == null || cell.isBlank() ? "" : plain(cell.text(), replaced));
            }
            line(text, cells, width);
        }
        CharsetEncoder encoder = encoding.charset().newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new SheetRejected(Reason.UNENCODABLE, "A cell holds a character that the chosen encoding cannot write.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.remaining() + 3);
        if (encoding.bom()) {
            byte[] bom = encoding == TextEncoding.UTF_16LE ? UTF16LE_BOM : UTF8_BOM;
            out.write(bom, 0, bom.length);
        }
        out.write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining());
        return new TextFile(out.toByteArray(), replaced[0], headerRows.size() + rows.size());
    }

    private static void line(StringBuilder text, List<String> cells, int width) {
        for (int i = 0; i < Math.max(width, cells.size()); i++) {
            if (i > 0) {
                text.append('\t');
            }
            if (i < cells.size()) {
                text.append(cells.get(i));
            }
        }
        text.append(LINE_END);
    }

    private static boolean needsReplacing(String cell) {
        return cell.indexOf('\t') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0 || cell.indexOf('\u0085') >= 0
                || cell.indexOf('\u2028') >= 0 || cell.indexOf('\u2029') >= 0;
    }

    /** How many cells of {@link #delimited} with these rows would have a tab or a line break replaced: what a confirmation page tells before the file is made. */
    public static int replacedCells(List<List<String>> headerRows, List<List<SheetCell>> rows) {
        int count = 0;
        for (List<String> header : headerRows) {
            for (String cell : header) {
                if (cell != null && needsReplacing(cell)) {
                    count++;
                }
            }
        }
        for (List<SheetCell> row : rows) {
            for (SheetCell cell : row) {
                if (cell != null && !cell.isBlank() && needsReplacing(cell.text())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String plain(String cell, int[] replaced) {
        if (!needsReplacing(cell)) {
            return cell;
        }
        replaced[0]++;
        return BREAKS.matcher(cell).replaceAll(" ");
    }

    // ------------------------------------------------------------------ the clipboard

    /**
     * The text that a person pastes into a spreadsheet: tab-separated, one line per row, LF between rows. A cell with a tab, a line break or
     * a double quote is wrapped in double quotes (a quote inside doubled), the way Google Sheets and Excel read a pasted cell, so nothing is
     * replaced. {@code headerRows} may be empty.
     */
    public static String clipboard(List<List<String>> headerRows, List<List<SheetCell>> rows) {
        StringBuilder text = new StringBuilder();
        for (List<String> header : headerRows) {
            boolean first = true;
            for (String cell : header) {
                text.append(first ? "" : "\t").append(quoted(cell == null ? "" : cell));
                first = false;
            }
            text.append('\n');
        }
        for (List<SheetCell> row : rows) {
            boolean first = true;
            for (SheetCell cell : row) {
                text.append(first ? "" : "\t").append(cell == null || cell.isBlank() ? "" : quoted(cell.text()));
                first = false;
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String quoted(String cell) {
        if (cell.indexOf('\t') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0 && cell.indexOf('"') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }
}
