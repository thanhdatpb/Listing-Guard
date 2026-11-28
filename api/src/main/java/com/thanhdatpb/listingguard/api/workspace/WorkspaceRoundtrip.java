package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.ListingCloner.Pair;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.HeaderGuess;
import com.thanhdatpb.listingguard.workspace.sheets.SheetCell;
import com.thanhdatpb.listingguard.workspace.sheets.SheetFormat;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetLimits;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import com.thanhdatpb.listingguard.workspace.sheets.SheetValidations;
import com.thanhdatpb.listingguard.workspace.sheets.SheetWriter;
import com.thanhdatpb.listingguard.workspace.sheets.TextEncoding;
import com.thanhdatpb.listingguard.workspace.sheets.WorkbookInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code workspace roundtrip} (ADR-063 §12): for a person who runs it on a real template on their own computer. It reads the file, builds the columns and
 * the guessed mapping the way an import would, writes the data rows back with {@link SheetWriter} into a new temporary folder, reads them again and
 * prints every cell that is not the same and every part of the package that changed. With {@code --clone-to} it prints how the columns of two
 * templates pair up, by {@link ListingCloner#pairs}. It writes nothing to a database and calls no network; its output names cells and values of the
 * person's own file, so it is for their screen, not for a log. Nothing in it is verified against a real Amazon file: that is what it is for.
 */
final class WorkspaceRoundtrip {

    private static final int MAX_DIFFS = 60;
    private static final int SHOW = 40;

    private WorkspaceRoundtrip() {
    }

    private record Loaded(byte[] bytes, SheetFormat format, String sheet, SheetGrid grid, int fieldRow, int firstDataRow, Template template) {
    }

    static void run(String file, String sheet, String fieldRow, String cloneTo, PrintStream out) throws IOException {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("--file=<template> is required.");
        }
        StandardTargets targets = StandardTargets.load();
        SheetLimits limits = SheetLimits.defaults();
        Integer row = fieldRow == null ? null : Integer.valueOf(fieldRow.strip());
        Loaded loaded = load(Path.of(file), sheet, row, targets, limits);
        out.println("File " + Path.of(file).getFileName() + ": " + loaded.format() + ", sheet '" + loaded.sheet() + "', field names in row "
                + loaded.fieldRow() + ", data from row " + loaded.firstDataRow() + ", " + loaded.template().columns().size() + " columns.");
        out.println("(Not verified with a real Amazon file: the mapping below is the guess of standard-targets.yaml.)");
        Path folder = Files.createTempDirectory("lg-roundtrip-");
        List<Map<String, String>> rows = dataRows(loaded);
        out.println(rows.size() + " data rows read.");
        boolean[] numeric = WorkspaceExports.numericColumns(loaded.template(), targets);
        List<List<SheetCell>> cells = new ArrayList<>();
        rows.forEach(data -> cells.add(WorkspaceExports.cells(loaded.template(), data, numeric)));
        if (loaded.format().isExcel()) {
            String name = "roundtrip." + (loaded.format() == SheetFormat.XLSM ? "xlsm" : "xlsx");
            byte[] written = SheetWriter.workbook(loaded.bytes(), loaded.sheet(), loaded.firstDataRow(), cells, limits);
            Files.write(folder.resolve(name), written);
            out.println("Wrote " + folder.resolve(name));
            compare(out, "workbook", loaded, SheetReader.read(written, loaded.sheet(), limits), false);
            parts(out, loaded.bytes(), written);
            var report = SheetValidations.read(loaded.bytes(), loaded.sheet(), loaded.firstDataRow(), limits);
            out.println("List validations: " + report.listRules() + " list rules read on " + report.allowed().size() + " columns, " + report.unresolved()
                    + " not resolved, " + report.extensionRules() + " in the extension part (not read), " + report.otherRules() + " of other kinds.");
        } else {
            out.println("The template has no workbook (a text file): only the text file is made.");
        }
        SheetWriter.TextFile text = SheetWriter.delimited(loaded.template().headerRows(), loaded.template().columns().size(), cells, TextEncoding.UTF_8);
        Files.write(folder.resolve("roundtrip.txt"), text.bytes());
        out.println("Wrote " + folder.resolve("roundtrip.txt") + " (" + text.replacedCells() + " cells had a tab or a line break replaced by a space).");
        compare(out, "text file", loaded, SheetReader.read(text.bytes(), "data", limits), true);
        if (cloneTo != null && !cloneTo.isBlank()) {
            Loaded second = load(Path.of(cloneTo), sheet, null, targets, limits);
            out.println();
            out.println("Columns of " + Path.of(file).getFileName() + " paired with " + Path.of(cloneTo).getFileName()
                    + " (the pairing of the clone: the n-th column of a standard field, then the same header):");
            clonePairs(out, loaded.template(), second.template());
        }
    }

    // ------------------------------------------------------------------ reading

    private static Loaded load(Path path, String sheetName, Integer fieldRowOption, StandardTargets targets, SheetLimits limits) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        WorkbookInfo info = SheetReader.inspect(bytes, limits);
        String sheet = sheetName;
        if (sheet == null) {
            sheet = info.sheets().stream().max(java.util.Comparator.comparingInt(s -> s.preview().rows().stream().mapToInt(List::size).sum()))
                    .orElseThrow().name();
        }
        SheetGrid grid = SheetReader.read(bytes, sheet, limits);
        HeaderGuess guess = HeaderGuess.of(grid);
        int fieldRow = fieldRowOption != null ? fieldRowOption : guess.fieldRow();
        int first = fieldRowOption != null ? fieldRow + 1 : guess.firstDataRow();
        if (fieldRow < 1 || first <= fieldRow) {
            throw new IllegalArgumentException("No row of field names was found: pass --field-row=<n>.");
        }
        List<String> names = grid.row(fieldRow);
        int width = TemplateService.trimmedWidth(names);
        List<TemplateColumn> columns = WorkspaceTemplates.columnsOf(names, width);
        Map<String, String> mapping = new LinkedHashMap<>();
        Set<String> single = new LinkedHashSet<>();
        for (TemplateColumn column : columns) {
            var guessed = targets.guess(Marketplace.AMAZON, column.header());
            if (guessed.isPresent() && (guessed.get().multiple() || single.add(guessed.get().key()))) {
                mapping.put(column.key(), guessed.get().key());
            }
        }
        List<List<String>> headerRows = new ArrayList<>();
        for (int r = 1; r < first; r++) {
            headerRows.add(grid.row(r));
        }
        Template template = new Template(null, null, null, TemplateKind.AMAZON_BULK, 1, info.format().name(), path.getFileName().toString(), null, sheet,
                fieldRow, first, headerRows, columns, new Mapping(mapping, Map.of(), Map.of()), WorkspaceTemplates.headerHash(columns), true, null);
        return new Loaded(bytes, info.format(), sheet, grid, fieldRow, first, template);
    }

    /** The data rows as the import reads them: a blank cell is not kept, a blank row is skipped. */
    private static List<Map<String, String>> dataRows(Loaded loaded) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<TemplateColumn> columns = loaded.template().columns();
        for (int r = loaded.firstDataRow(); r <= loaded.grid().rowCount(); r++) {
            List<String> cells = loaded.grid().row(r);
            Map<String, String> data = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < cells.size(); i++) {
                if (cells.get(i) != null && !cells.get(i).isBlank()) {
                    data.put(columns.get(i).key(), cells.get(i));
                }
            }
            if (!data.isEmpty()) {
                rows.add(data);
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ comparing

    private static String shown(String value) {
        String clean = value.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
        return clean.length() > SHOW ? clean.substring(0, SHOW) + "..." : clean;
    }

    private static boolean sameNumber(String a, String b) {
        try {
            return new BigDecimal(a.strip()).compareTo(new BigDecimal(b.strip())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Compares rows above the data and every data row (same order as the file: the roundtrip does not reorder). */
    private static void compare(PrintStream out, String what, Loaded loaded, SheetGrid again, boolean replaceBreaks) {
        int width = loaded.template().columns().size();
        int different = 0;
        int numberOnly = 0;
        int cellsCompared = 0;
        List<String> lines = new ArrayList<>();
        List<Integer> dataRowNumbers = new ArrayList<>();
        for (int r = loaded.firstDataRow(); r <= loaded.grid().rowCount(); r++) {
            if (!loaded.grid().row(r).stream().allMatch(c -> c == null || c.isBlank())) {
                dataRowNumbers.add(r);
            }
        }
        List<Integer> sourceRows = new ArrayList<>();
        for (int r = 1; r < loaded.firstDataRow(); r++) {
            sourceRows.add(r);
        }
        int above = sourceRows.size();
        sourceRows.addAll(dataRowNumbers);
        for (int i = 0; i < sourceRows.size(); i++) {
            int sourceRow = sourceRows.get(i);
            int targetRow = i < above ? sourceRow : loaded.firstDataRow() + (i - above);
            List<String> before = loaded.grid().row(sourceRow);
            List<String> after = again.row(targetRow);
            for (int c = 0; c < width; c++) {
                String a = c < before.size() && before.get(c) != null ? before.get(c) : "";
                String b = c < after.size() && after.get(c) != null ? after.get(c) : "";
                String expected = replaceBreaks ? a.replaceAll("[\t\r\n\u0085\u2028\u2029]", " ") : a;
                cellsCompared++;
                if (expected.equals(b)) {
                    continue;
                }
                if (sameNumber(a, b)) {
                    numberOnly++;
                    continue;
                }
                different++;
                if (lines.size() < MAX_DIFFS) {
                    lines.add("  " + WorkspaceTemplates.letters(c) + sourceRow + ": '" + shown(a) + "' became '" + shown(b) + "'");
                }
            }
        }
        out.println("Compared the " + what + ": " + cellsCompared + " cells, " + different + " different, " + numberOnly
                + " the same number written another way (for example 19.50 and 19.5).");
        lines.forEach(out::println);
        if (different > lines.size()) {
            out.println("  ... and " + (different - lines.size()) + " more.");
        }
    }

    private static Map<String, String> hashes(byte[] zip) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                try {
                    parts.put(entry.getName(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return parts;
    }

    private static void parts(PrintStream out, byte[] before, byte[] after) throws IOException {
        Map<String, String> a = hashes(before);
        Map<String, String> b = hashes(after);
        Set<String> all = new TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        int same = 0;
        List<String> changed = new ArrayList<>();
        for (String name : all) {
            if (!b.containsKey(name)) {
                changed.add("  removed: " + name);
            } else if (!a.containsKey(name)) {
                changed.add("  added:   " + name);
            } else if (a.get(name).equals(b.get(name))) {
                same++;
            } else {
                changed.add("  changed: " + name + (name.toLowerCase(Locale.ROOT).contains("vba") ? "   <-- THE MACRO PROJECT CHANGED" : ""));
            }
        }
        out.println("Package parts: " + same + " unchanged, " + changed.size() + " not. (The sheet written, shared strings and styles change by design.)");
        changed.forEach(out::println);
        out.println("  macro project: " + (a.keySet().stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("vba"))
                ? (a.entrySet().stream().filter(e -> e.getKey().toLowerCase(Locale.ROOT).contains("vba")).allMatch(e -> e.getValue().equals(b.get(e.getKey())))
                        ? "kept byte for byte" : "CHANGED") : "none"));
    }

    // ------------------------------------------------------------------ the clone's pairing

    private static void clonePairs(PrintStream out, Template source, Template target) {
        List<Pair> pairs = ListingCloner.pairs(source, target);
        Set<String> sourceDone = new LinkedHashSet<>();
        Set<String> targetDone = new LinkedHashSet<>();
        out.println("  pairs (" + pairs.size() + "):");
        for (Pair p : pairs) {
            out.println("    " + p.sourceHeader() + "  ->  " + p.targetHeader() + (p.target() == null ? "   [same header]" : "   [" + p.target() + "]"));
            sourceDone.add(p.sourceKey());
            targetDone.add(p.targetKey());
        }
        out.println("  columns of the first template with nowhere to go:");
        source.columns().stream().filter(c -> !sourceDone.contains(c.key())).forEach(c -> out.println("    " + (c.header().isBlank() ? c.key() : c.header())
                + (source.mapping().targets().containsKey(c.key()) ? "   [" + source.mapping().targets().get(c.key()) + "]" : "")));
        out.println("  columns of the second template that nothing fills:");
        target.columns().stream().filter(c -> !targetDone.contains(c.key())).forEach(c -> out.println("    " + (c.header().isBlank() ? c.key() : c.header())
                + (target.mapping().targets().containsKey(c.key()) ? "   [" + target.mapping().targets().get(c.key()) + "]" : "")));
    }
}
