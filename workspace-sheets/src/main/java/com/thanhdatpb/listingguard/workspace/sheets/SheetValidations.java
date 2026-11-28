package com.thanhdatpb.listingguard.workspace.sheets;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The lists of valid values that a template's data validations hold (ADR-063). A validation of the kind "list" is read either from its
 * literal list or from the cells it points to, in this sheet or another, directly or through a defined name. Nothing is guessed: a rule
 * that points anywhere else (a formula such as INDIRECT or OFFSET, a name that is not a plain range, a list too long) is counted as not
 * resolved, and a rule that Excel keeps in the extension part of the sheet (the form that older writers use for a list on another sheet)
 * is counted as not read. The report says how much was read so the screen can say how complete the check is.
 *
 * <p>Nothing is evaluated and no macro is run: cell values are the saved ones. The class takes the lock of {@link XlsxReader}.
 */
public final class SheetValidations {

    /** The most values one rule may list; a longer list is not resolved. */
    public static final int MAX_LIST = 5_000;
    /** The most rules of a sheet that are read. */
    public static final int MAX_RULES = 2_000;

    private SheetValidations() {
    }

    /**
     * @param allowed       the valid values of a column (0-based) of the data rows, from every list rule that covers it
     * @param listRules     how many list rules of the sheet were read
     * @param unresolved    how many list rules could not be turned into values
     * @param otherRules    how many rules are not lists (whole numbers, dates, lengths...): not read
     * @param extensionRules how many rules sit in the sheet's extension part and are not read
     */
    public record Report(Map<Integer, Set<String>> allowed, int listRules, int unresolved, int otherRules, int extensionRules) {

        public Report {
            Map<Integer, Set<String>> copy = new LinkedHashMap<>();
            allowed.forEach((column, values) -> copy.put(column, Set.copyOf(values)));
            allowed = java.util.Collections.unmodifiableMap(copy);
        }

        /** True when the sheet has no rule that this reads: the check then has nothing to compare with. */
        public boolean empty() {
            return allowed.isEmpty();
        }

        /** True when every rule that the sheet declares was read. */
        public boolean complete() {
            return unresolved == 0 && extensionRules == 0;
        }
    }

    /**
     * Reads the list rules of one sheet that cover rows from {@code firstDataRow} (1-based) down.
     *
     * @throws SheetRejected for a file that is not a readable workbook or has no such sheet
     */
    public static Report read(byte[] workbook, String sheetName, int firstDataRow, SheetLimits limits) {
        SheetFormat format = SheetReader.detect(workbook, limits);
        if (!format.isExcel()) {
            return new Report(Map.of(), 0, 0, 0, 0);
        }
        synchronized (XlsxReader.LOCK) {
            XlsxReader.configure(limits);
            try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
                XSSFSheet sheet = book.getSheet(sheetName);
                if (sheet == null) {
                    throw new SheetRejected(Reason.SHEET_NOT_FOUND, "The workbook has no sheet with this name.");
                }
                return report(book, sheet, firstDataRow, limits);
            } catch (SheetRejected e) {
                throw e;
            } catch (Exception | StackOverflowError e) {
                throw XlsxReader.map(e);
            }
        }
    }

    private static Report report(XSSFWorkbook book, XSSFSheet sheet, int firstDataRow, SheetLimits limits) {
        Map<Integer, Set<String>> allowed = new LinkedHashMap<>();
        int listRules = 0;
        int unresolved = 0;
        int others = 0;
        DataFormatter formatter = new DataFormatter(Locale.US);
        int[] budget = {limits.maxRows() * 5};
        List<? extends DataValidation> rules = sheet.getDataValidations();
        for (DataValidation rule : rules.subList(0, Math.min(rules.size(), MAX_RULES))) {
            DataValidationConstraint constraint = rule.getValidationConstraint();
            if (constraint.getValidationType() != DataValidationConstraint.ValidationType.LIST) {
                others++;
                continue;
            }
            CellRangeAddress[] regions = rule.getRegions().getCellRangeAddresses();
            if (!covers(regions, firstDataRow - 1)) {
                continue;
            }
            listRules++;
            Set<String> values = values(book, sheet, constraint, formatter, budget);
            if (values == null) {
                unresolved++;
                continue;
            }
            for (CellRangeAddress region : regions) {
                if (region.getLastRow() < firstDataRow - 1) {
                    continue;
                }
                for (int column = region.getFirstColumn(); column <= Math.min(region.getLastColumn(), limits.maxColumns() - 1); column++) {
                    allowed.computeIfAbsent(column, c -> new LinkedHashSet<>()).addAll(values);
                }
            }
        }
        int extension = extensionRules(sheet);
        return new Report(allowed, listRules, unresolved, others, extension);
    }

    private static boolean covers(CellRangeAddress[] regions, int firstDataIndex) {
        for (CellRangeAddress region : regions) {
            if (region.getLastRow() >= firstDataIndex) {
                return true;
            }
        }
        return false;
    }

    /** The values of a list rule, or null when they cannot be read without guessing. */
    private static Set<String> values(XSSFWorkbook book, XSSFSheet sheet, DataValidationConstraint constraint, DataFormatter formatter,
                                      int[] budget) {
        String[] literal = constraint.getExplicitListValues();
        if (literal != null) {
            Set<String> values = new LinkedHashSet<>();
            for (String value : literal) {
                values.add(value.strip());
            }
            return values.size() > MAX_LIST ? null : values;
        }
        String formula = constraint.getFormula1();
        if (formula == null || formula.isBlank()) {
            return null;
        }
        String reference = formula.strip();
        if (reference.startsWith("=")) {
            reference = reference.substring(1);
        }
        Name name = book.getName(reference);
        if (name != null) {
            try {
                reference = name.getRefersToFormula();
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (reference == null || reference.contains("(") || reference.contains(",") || reference.contains("[")) {
            return null;
        }
        try {
            AreaReference area = new AreaReference(reference, SpreadsheetVersion.EXCEL2007);
            CellReference first = area.getFirstCell();
            Sheet target = first.getSheetName() == null ? sheet : book.getSheet(first.getSheetName());
            if (target == null) {
                return null;
            }
            CellReference last = area.getLastCell();
            int cells = (last.getRow() - first.getRow() + 1) * (last.getCol() - first.getCol() + 1);
            if (cells > MAX_LIST || cells > budget[0]) {
                return null;
            }
            budget[0] -= cells;
            Set<String> values = new LinkedHashSet<>();
            for (int r = first.getRow(); r <= last.getRow(); r++) {
                Row row = target.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = first.getCol(); c <= last.getCol(); c++) {
                    Cell cell = row.getCell(c);
                    String text = cell == null ? "" : saved(cell, formatter).strip();
                    if (!text.isEmpty()) {
                        values.add(text);
                    }
                }
            }
            return values;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The value a cell was saved with, formatted as Excel shows it; a formula's last saved result, never a recalculation. */
    private static String saved(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                    cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** Rules kept in the extension list of the sheet's XML: counted by their tag, never parsed. */
    private static int extensionRules(XSSFSheet sheet) {
        var ext = sheet.getCTWorksheet().getExtLst();
        if (ext == null) {
            return 0;
        }
        String xml = ext.xmlText();
        int count = 0;
        for (int at = xml.indexOf("dataValidation "); at >= 0; at = xml.indexOf("dataValidation ", at + 1)) {
            count++;
        }
        return count;
    }

    /** The values of a column that are not among its valid values, for a column with a list. A blank value is never reported. */
    public static List<String> invalid(Set<String> allowed, List<String> values) {
        List<String> bad = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String clean = value == null ? "" : value.strip();
            if (!clean.isEmpty() && !allowed.contains(clean) && seen.add(clean)) {
                bad.add(clean);
            }
        }
        return bad;
    }
}
