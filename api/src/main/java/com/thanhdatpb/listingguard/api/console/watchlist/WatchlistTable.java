package com.thanhdatpb.listingguard.api.console.watchlist;

import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The watchlist as a table (ADR-031): what a seller pastes from a Google Sheet or uploads as CSV/TSV, and what "Export CSV"
 * writes. The columns are mark, aliases, classes, serial, note and enabled. A first row whose first cell names the mark column is
 * a header and the columns are found by name (in English or Vietnamese, any order, unknown ones ignored); without one the columns
 * are taken in that order. Several aliases in one cell are separated by {@code ;}, {@code |} or a line break, several classes by
 * {@code ;}, {@code ,} or spaces.
 *
 * <p>A cell that starts with {@code =}, {@code +}, {@code -} or {@code @} is written with a leading {@code '} so that a spreadsheet
 * shows it instead of running it, and that {@code '} is removed again when the table is read.
 */
public final class WatchlistTable {

    /** The columns, in the order they are taken when there is no header and in the order they are exported. */
    public enum Column { MARK, ALIASES, CLASSES, SERIAL, NOTE, ENABLED }

    /** The names the export writes; a header may use any of {@link #NAMES} instead. */
    static final List<String> HEADER = List.of("mark", "aliases", "classes", "serial", "note", "enabled");

    private static final Map<String, Column> NAMES = names();
    private static final Pattern ALIAS_SEPARATOR = Pattern.compile("[;|\\n]");
    private static final Pattern CLASS_SEPARATOR = Pattern.compile("[;,\\s]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /** One row of the table: its line, the entry it describes, or why it could not be read ({@code errorCode} and its value). */
    public record Row(int line, EntryInput entry, String errorCode, String errorValue) {
    }

    /** How a row of an import changes the list. */
    public enum Outcome { ADDED, UPDATED, UNCHANGED, REFUSED }

    /**
     * The list after an import, and what each row does to it.
     *
     * @param entries the whole list to store: the current entries, updated, then the new ones in the order of the rows
     * @param rows    one per readable or unreadable row, in the order of the table
     */
    public record Merge(List<EntryInput> entries, List<MergedRow> rows) {
    }

    /**
     * @param index the row's place in {@link Merge#entries()}, -1 for a row that could not be read
     */
    public record MergedRow(Row row, Outcome outcome, int index) {
    }

    private WatchlistTable() {
    }

    /**
     * The rows of a table; blank rows are skipped.
     *
     * @throws DelimitedText.FormatException if a quoted cell is never closed
     */
    public static List<Row> read(String text, char delimiter) {
        List<DelimitedText.Row> rows = DelimitedText.parse(text, delimiter).rows();
        Map<Column, Integer> columns = null;
        List<Row> result = new ArrayList<>();
        for (DelimitedText.Row row : rows) {
            if (row.blank()) {
                continue;
            }
            if (columns == null) {
                columns = header(row.cells());
                if (columns != null) {
                    continue;
                }
                columns = positional();
            }
            result.add(row(row.line(), row.cells(), columns));
        }
        return result;
    }

    /** Merges imported rows into the current list by the normalized form of the mark ({@code normalize}). */
    public static Merge merge(List<EntryView> current, List<Row> rows, Function<String, String> normalize) {
        List<EntryInput> entries = new ArrayList<>();
        Map<String, Integer> existing = new HashMap<>();
        for (EntryView entry : current) {
            existing.putIfAbsent(normalize.apply(entry.mark()), entries.size());
            entries.add(input(entry));
        }
        List<MergedRow> merged = new ArrayList<>();
        for (Row row : rows) {
            if (row.entry() == null) {
                merged.add(new MergedRow(row, Outcome.REFUSED, -1));
                continue;
            }
            Integer index = existing.remove(normalize.apply(row.entry().mark()));
            if (index == null) {
                EntryInput added = row.entry().enabled() == null ? withEnabled(row.entry(), true) : row.entry();
                merged.add(new MergedRow(row, Outcome.ADDED, entries.size()));
                entries.add(added);
                continue;
            }
            EntryInput before = entries.get(index);
            EntryInput after = row.entry().enabled() == null ? withEnabled(row.entry(), before.enabled()) : row.entry();
            entries.set(index, after);
            merged.add(new MergedRow(row, same(before, after) ? Outcome.UNCHANGED : Outcome.UPDATED, index));
        }
        return new Merge(entries, merged);
    }

    /** The entry a stored one is, as it would be sent back. */
    public static EntryInput input(EntryView entry) {
        return new EntryInput(entry.mark(), entry.aliases(), entry.niceClasses(), entry.serialNumber(), entry.note(),
                entry.enabled());
    }

    /** The list as CSV (UTF-8 with a byte order mark, so a spreadsheet reads Vietnamese letters), with a header row. */
    public static String csv(List<EntryView> entries) {
        StringBuilder out = new StringBuilder("﻿");
        line(out, HEADER);
        for (EntryView entry : entries) {
            line(out, List.of(entry.mark(), String.join("; ", entry.aliases()),
                    String.join("; ", entry.niceClasses().stream().map(String::valueOf).toList()),
                    Objects.toString(entry.serialNumber(), ""), Objects.toString(entry.note(), ""),
                    String.valueOf(entry.enabled())));
        }
        return out.toString();
    }

    /** Splits a cell of aliases. */
    public static List<String> aliases(String cell) {
        return Arrays.stream(ALIAS_SEPARATOR.split(cell == null ? "" : cell)).map(String::strip).filter(a -> !a.isEmpty()).toList();
    }

    /**
     * Splits a cell of Nice classes.
     *
     * @return null when a part is not a whole number
     */
    public static List<Integer> classes(String cell) {
        List<Integer> classes = new ArrayList<>();
        for (String part : CLASS_SEPARATOR.split(cell == null ? "" : cell.strip())) {
            if (part.isEmpty()) {
                continue;
            }
            if (!part.matches("[0-9]{1,3}")) {
                return null;
            }
            classes.add(Integer.parseInt(part));
        }
        return classes;
    }

    private static Row row(int line, List<String> cells, Map<Column, Integer> columns) {
        String mark = cell(cells, columns, Column.MARK);
        String classesCell = cell(cells, columns, Column.CLASSES);
        List<Integer> classes = classes(classesCell);
        if (classes == null) {
            return new Row(line, null, "classes-format", classesCell);
        }
        String enabledCell = cell(cells, columns, Column.ENABLED);
        Boolean enabled = enabled(enabledCell);
        if (enabled == null && !enabledCell.isBlank()) {
            return new Row(line, null, "enabled-format", enabledCell);
        }
        return new Row(line, new EntryInput(mark, aliases(cell(cells, columns, Column.ALIASES)), classes,
                blankToNull(cell(cells, columns, Column.SERIAL)), blankToNull(cell(cells, columns, Column.NOTE)), enabled),
                null, null);
    }

    private static Boolean enabled(String cell) {
        return switch (fold(cell)) {
            case "true", "yes", "1", "x", "on", "bat", "co" -> true;
            case "false", "no", "0", "off", "tat", "khong" -> false;
            default -> null;
        };
    }

    private static String cell(List<String> cells, Map<Column, Integer> columns, Column column) {
        Integer index = columns.get(column);
        if (index == null || index >= cells.size()) {
            return "";
        }
        return unguard(cells.get(index).strip());
    }

    private static Map<Column, Integer> header(List<String> cells) {
        if (cells.isEmpty() || NAMES.get(fold(cells.get(0))) != Column.MARK) {
            return null;
        }
        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        for (int i = 0; i < cells.size(); i++) {
            Column column = NAMES.get(fold(cells.get(i)));
            if (column != null) {
                columns.putIfAbsent(column, i);
            }
        }
        return columns;
    }

    private static Map<Column, Integer> positional() {
        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        for (Column column : Column.values()) {
            columns.put(column, column.ordinal());
        }
        return columns;
    }

    private static Map<String, Column> names() {
        Map<String, Column> names = new HashMap<>();
        for (String name : List.of("mark", "marks", "trademark", "nhanhieu", "ten")) {
            names.put(name, Column.MARK);
        }
        for (String name : List.of("alias", "aliases", "variant", "variants", "bienthe")) {
            names.put(name, Column.ALIASES);
        }
        for (String name : List.of("class", "classes", "niceclass", "niceclasses", "nice", "nhom", "nhomnice")) {
            names.put(name, Column.CLASSES);
        }
        for (String name : List.of("serial", "serialnumber", "serialno", "sodon")) {
            names.put(name, Column.SERIAL);
        }
        for (String name : List.of("note", "notes", "reason", "ghichu", "lydo")) {
            names.put(name, Column.NOTE);
        }
        for (String name : List.of("enabled", "active", "bat", "dangbat")) {
            names.put(name, Column.ENABLED);
        }
        return Map.copyOf(names);
    }

    /** Lower case, no accents, no spaces, underscores or hyphens: "Nhãn hiệu" and "nhan_hieu" are one name. */
    static String fold(String text) {
        String lower = Normalizer.normalize(text == null ? "" : text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return DIACRITICS.matcher(lower).replaceAll("").replace("đ", "d").replaceAll("[\\s_\\-.]", "");
    }

    private static boolean same(EntryInput a, EntryInput b) {
        return a.mark().equals(b.mark()) && a.aliases().equals(b.aliases()) && a.niceClasses().equals(b.niceClasses())
                && Objects.equals(a.serialNumber(), b.serialNumber()) && Objects.equals(a.note(), b.note())
                && Objects.equals(a.enabled(), b.enabled());
    }

    private static EntryInput withEnabled(EntryInput entry, Boolean enabled) {
        return new EntryInput(entry.mark(), entry.aliases(), entry.niceClasses(), entry.serialNumber(), entry.note(), enabled);
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String cell = guard(cells.get(i));
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append("\r\n");
    }

    /** A cell a spreadsheet would run as a formula gets a leading {@code '}. */
    static String guard(String cell) {
        return !cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0 ? "'" + cell : cell;
    }

    /** Removes the {@code '} that {@link #guard} adds. */
    static String unguard(String cell) {
        return cell.length() > 1 && cell.charAt(0) == '\'' && "=+-@".indexOf(cell.charAt(1)) >= 0 ? cell.substring(1) : cell;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
