package com.thanhdatpb.listingguard.api.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The synthetic template of the export tests (ADR-063), built with POI: a settings row, a label row, the field-name row, data validations (a
 * list on another sheet for the product type and a literal list for the size), a second sheet with the lists, a defined name, numeric cells for
 * the price and the quantity, and, through {@link WorkspaceFixtures#asMacroEnabled}, an inert part that stands for the macro project. Nothing
 * is read from a real Amazon template: every sheet name and field name is invented, and every test says "not verified with a real file".
 */
final class ExportFixtures {

    static final List<String> FIELDS = WorkspaceFixtures.FIELDS;
    /** The first data row of the template (1-based): row 1 settings, row 2 labels, row 3 field names. */
    static final int FIRST_DATA_ROW = 4;

    private ExportFixtures() {
    }

    /** A text cell is a String; a numeric cell is a Number; null or "" is empty. */
    static List<List<Object>> dataRows(String prefix) {
        return List.of(
                List.of(prefix + "P1", "SHIRT", "Synthetic shirt", "Nobrand", "parent", "", "SizeColor", "", "", "", "A synthetic shirt", "", "", "", "", ""),
                List.of(prefix + "P1-S", "SHIRT", "Synthetic shirt S", "Nobrand", "child", prefix + "P1", "SizeColor", "Soft", "Warm", "gift", "", "S", "Blue",
                        19.99, 5, "yes"),
                List.of(prefix + "P1-M", "SHIRT", "Synthetic shirt M", "Nobrand", "child", prefix + "P1", "SizeColor", "Soft", "Warm", "gift", "", "M", "Blue",
                        19.99, 7, "yes"),
                // a line break and a tab inside a cell, and a code that must stay a text
                List.of(prefix + "S2", "MUG", "Synthetic mug", "Nobrand", "", "", "", "Ceramic", "", "cup", "Line one\nLine two\tafter the tab", "", "", 9.5, 3, "007"),
                // a title with Vietnamese letters, a whole price and a price with a trailing zero
                List.of(prefix + "V1", "SHIRT", "Áo thun đẹp", "Nobrand", "", "", "", "", "", "", "Mô tả ngắn", "L", "", 12, 1, ""));
    }

    static byte[] flatFile(String prefix) throws IOException {
        return flatFile(dataRows(prefix));
    }

    static byte[] flatFile(List<List<Object>> data) throws IOException {
        return flatFile(FIELDS, data, true);
    }

    static byte[] flatFile(List<String> fields, List<List<Object>> data, boolean validations) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("Instructions").createRow(0).createCell(0).setCellValue("Read me");
            Sheet template = book.createSheet("Template");
            row(template, 0, List.of("TemplateType=synthetic", "Version=0.0", "Settings=none"));
            row(template, 1, fields.stream().map(f -> "Label of " + f).map(o -> (Object) o).toList());
            row(template, 2, fields.stream().map(f -> (Object) f).toList());
            CellStyle money = book.createCellStyle();
            money.setDataFormat(book.createDataFormat().getFormat("0.00"));
            int index = 3;
            for (List<Object> cells : data) {
                row(template, index++, cells);
            }
            Sheet values = book.createSheet("Valid Values");
            row(values, 0, List.of("SHIRT", "MUG"));
            if (validations) {
                DataValidationHelper helper = template.getDataValidationHelper();
                DataValidation types = helper.createValidation(helper.createFormulaListConstraint("'Valid Values'!$A$1:$B$1"),
                        new CellRangeAddressList(3, 5000, 1, 1));
                template.addValidationData(types);
                DataValidation sizes = helper.createValidation(helper.createExplicitListConstraint(new String[] {"S", "M", "L"}),
                        new CellRangeAddressList(3, 5000, 11, 11));
                template.addValidationData(sizes);
            }
            org.apache.poi.ss.usermodel.Name name = book.createName();
            name.setNameName("ProductTypes");
            name.setRefersToFormula("'Valid Values'!$A$1:$B$1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    static byte[] flatFileXlsm(String prefix) throws IOException {
        return WorkspaceFixtures.asMacroEnabled(flatFile(prefix));
    }

    private static void row(Sheet sheet, int index, List<Object> cells) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < cells.size(); i++) {
            Object value = cells.get(i);
            if (value == null || value.toString().isEmpty()) {
                continue;
            }
            if (value instanceof Number number) {
                row.createCell(i).setCellValue(number.doubleValue());
            } else {
                row.createCell(i).setCellValue(value.toString());
            }
        }
    }
}
