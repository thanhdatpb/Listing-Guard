package com.thanhdatpb.listingguard.workspace.sheets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SheetWriterTest {

    private static final SheetLimits LIMITS = SheetLimits.defaults();

    private static List<List<SheetCell>> rows(String[]... rows) {
        List<List<SheetCell>> all = new ArrayList<>();
        for (String[] row : rows) {
            List<SheetCell> cells = new ArrayList<>();
            for (String value : row) {
                cells.add(SheetCell.numeric(value).orElseGet(() -> SheetCell.text(value)));
            }
            all.add(cells);
        }
        return all;
    }

    @Test
    void aWrittenWorkbookKeepsTheRowsAboveTheOtherSheetsAndTheRulesAndReplacesTheDataRows() throws IOException {
        byte[] template = TestWorkbooks.withValidations();

        byte[] written = SheetWriter.workbook(template, "Template", 4, rows(new String[] {"N-1", "SHIRT", "S", "Blue", "19.99"},
                new String[] {"N-2", "MUG", "L", "Red", "5"}), LIMITS);

        SheetGrid grid = SheetReader.read(written, "Template", LIMITS);
        assertThat(grid.rowCount()).isEqualTo(5);
        assertThat(grid.row(1)).containsExactly("TemplateType=synthetic", "Version=0.0");
        assertThat(grid.row(3)).containsExactly("item_sku", "feed_product_type", "size_name", "color_name", "standard_price");
        assertThat(grid.row(4)).containsExactly("N-1", "SHIRT", "S", "Blue", "19.99");
        // the price column has the template's 0.00 format, which a written number takes
        assertThat(grid.row(5)).containsExactly("N-2", "MUG", "L", "Red", "5.00");
        assertThat(SheetReader.read(written, "Valid Values", LIMITS).row(1)).containsExactly("SHIRT", "MUG", "Blue");
        // the validations are as they were
        SheetValidations.Report before = SheetValidations.read(template, "Template", 4, LIMITS);
        SheetValidations.Report after = SheetValidations.read(written, "Template", 4, LIMITS);
        assertThat(after.allowed()).isEqualTo(before.allowed());
        assertThat(after.listRules()).isEqualTo(3);
        // the defined name survived
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(written))) {
            assertThat(book.getName("Colors")).isNotNull();
        }
    }

    @Test
    void moreRowsThanTheTemplateHadAndFewerBothWork() throws IOException {
        byte[] template = TestWorkbooks.withValidations();

        byte[] none = SheetWriter.workbook(template, "Template", 4, List.of(), LIMITS);
        assertThat(SheetReader.read(none, "Template", LIMITS).rowCount()).isEqualTo(3);

        List<List<SheetCell>> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(List.of(SheetCell.text("K-" + i)));
        }
        byte[] full = SheetWriter.workbook(template, "Template", 4, many, LIMITS);
        SheetGrid grid = SheetReader.read(full, "Template", LIMITS);
        assertThat(grid.rowCount()).isEqualTo(303);
        assertThat(grid.row(303)).containsExactly("K-299");
    }

    @Test
    void theMacroProjectIsKeptByteForByteAndTheFileStaysMacroEnabled() throws IOException {
        byte[] xlsm = TestWorkbooks.asMacroEnabled(TestWorkbooks.withValidations());
        byte[] macro = TestWorkbooks.part(xlsm, "xl/vbaProject.bin");

        byte[] written = SheetWriter.workbook(xlsm, "Template", 4, rows(new String[] {"N-1", "SHIRT", "S", "Blue", "1"}), LIMITS);

        assertThat(SheetReader.detect(written, LIMITS)).isEqualTo(SheetFormat.XLSM);
        assertThat(TestWorkbooks.part(written, "xl/vbaProject.bin")).isEqualTo(macro);
        assertThat(new String(TestWorkbooks.part(written, "[Content_Types].xml"), StandardCharsets.UTF_8)).contains("macroEnabled");
    }

    @Test
    void aNumberIsANumericCellAndATextThatLooksLikeACodeStaysText() throws IOException {
        byte[] written = SheetWriter.workbook(TestWorkbooks.withValidations(), "Template", 4,
                rows(new String[] {"007", "=1+1", "5", "19.99", "123456789012345678"}), LIMITS);

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(written))) {
            var row = book.getSheet("Template").getRow(3);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("007");
            // a text that starts with = is a text, never a formula
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("=1+1");
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(5.0);
            assertThat(row.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(19.99);
            // more than 15 digits do not fit a number: kept exact as a text
            assertThat(row.getCell(4).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("123456789012345678");
        }
    }

    @Test
    void aWrittenCellTakesTheFormatOfTheTemplateCellOfItsColumn() throws IOException {
        byte[] written = SheetWriter.workbook(TestWorkbooks.withValidations(), "Template", 4,
                rows(new String[] {"N-1", "SHIRT", "S", "Blue", "19.5"}), LIMITS);

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(written))) {
            assertThat(book.getSheet("Template").getRow(3).getCell(4).getCellStyle().getDataFormatString()).isEqualTo("0.00");
            assertThat(SheetReader.read(written, "Template", LIMITS).row(4).get(4)).isEqualTo("19.50");
        }
    }

    @Test
    void aCellLongerThanExcelHoldsRefusesTheFile() throws IOException {
        List<List<SheetCell>> rows = List.of(List.of(SheetCell.text("x".repeat(SheetWriter.MAX_CELL_CHARS + 1))));
        assertThatThrownBy(() -> SheetWriter.workbook(TestWorkbooks.withValidations(), "Template", 4, rows, LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.CELL_TOO_LONG))
                .hasMessageNotContaining("xxxx");
    }

    @Test
    void anUnknownSheetAndATextTemplateAreRefused() throws IOException {
        assertThatThrownBy(() -> SheetWriter.workbook(TestWorkbooks.withValidations(), "Nope", 4, List.of(), LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.SHEET_NOT_FOUND));
        byte[] tsv = "a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SheetWriter.workbook(tsv, "data", 2, List.of(), LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void theLimitsOfTheReaderApplyToTheWriter() throws IOException {
        byte[] template = TestWorkbooks.withValidations();
        assertThatThrownBy(() -> SheetWriter.workbook(template, "Template", 4, rows(new String[] {"a"}, new String[] {"b"}),
                LIMITS.withMaxRows(4)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_ROWS));
        assertThatThrownBy(() -> SheetWriter.workbook(template, "Template", 4, List.of(), LIMITS.withMaxBytes(100)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThatThrownBy(() -> SheetWriter.workbook("not a workbook".getBytes(StandardCharsets.UTF_8), "Template", 4, List.of(), LIMITS))
                .isInstanceOf(SheetRejected.class);
    }

    // ------------------------------------------------------------------ text

    @Test
    void aTextFileHasTheHeaderRowsVerbatimThenTheDataRowsWithEveryColumn() {
        SheetWriter.TextFile file = SheetWriter.delimited(List.of(List.of("TemplateType=synthetic"), List.of("Seller SKU", "Name"),
                List.of("item_sku", "item_name", "standard_price")), 3,
                rows(new String[] {"P-1", "Mug", "19.50"}, new String[] {"P-2", "", ""}), TextEncoding.UTF_8);

        assertThat(new String(file.bytes(), StandardCharsets.UTF_8)).isEqualTo("TemplateType=synthetic\t\t\r\nSeller SKU\tName\t\r\n"
                + "item_sku\titem_name\tstandard_price\r\nP-1\tMug\t19.5\r\nP-2\t\t\r\n");
        assertThat(file.rows()).isEqualTo(5);
        assertThat(file.replacedCells()).isZero();
    }

    @Test
    void numbersAreWrittenInTheirPlainFormWithoutPointZeroOrAnExponent() {
        assertThat(SheetCell.numeric("5.0").orElseThrow().text()).isEqualTo("5");
        assertThat(SheetCell.numeric("5.00").orElseThrow().text()).isEqualTo("5");
        assertThat(SheetCell.numeric("1.2E+11").orElseThrow().text()).isEqualTo("120000000000");
        assertThat(SheetCell.numeric("-0").orElseThrow().text()).isEqualTo("0");
        assertThat(SheetCell.numeric("19.99").orElseThrow().text()).isEqualTo("19.99");
        assertThat(SheetCell.number(new BigDecimal("1E+3")).text()).isEqualTo("1000");
        assertThat(SheetCell.numeric("007")).isEmpty();
        assertThat(SheetCell.numeric("1,5")).isEmpty();
        assertThat(SheetCell.numeric("12abc")).isEmpty();
        assertThat(SheetCell.numeric("")).isEmpty();
        assertThat(SheetCell.numeric("1234567890123456")).isEmpty();
    }

    @Test
    void aTabOrALineBreakInACellBecomesOneSpaceAndTheCellsAreCounted() {
        SheetWriter.TextFile file = SheetWriter.delimited(List.of(List.of("h\tx")), 2,
                List.of(List.of(SheetCell.text("line one\r\nline two"), SheetCell.text("a\tb\nc")), List.of(SheetCell.text("plain"))),
                TextEncoding.UTF_8);

        assertThat(new String(file.bytes(), StandardCharsets.UTF_8)).isEqualTo("h x\t\r\nline one line two\ta b c\r\nplain\t\r\n");
        // the header cell, and the two cells of the first row
        assertThat(file.replacedCells()).isEqualTo(3);
    }

    @Test
    void theEncodingIsChosenAndACharacterItCannotWriteRefusesTheFile() {
        List<List<SheetCell>> viet = List.of(List.of(SheetCell.text("Áo thun đẹp")));

        byte[] utf8 = SheetWriter.delimited(List.of(), 1, viet, TextEncoding.UTF_8).bytes();
        assertThat(new String(utf8, StandardCharsets.UTF_8)).isEqualTo("Áo thun đẹp\r\n");
        assertThat(utf8[0]).isNotEqualTo((byte) 0xEF);

        byte[] bom = SheetWriter.delimited(List.of(), 1, viet, TextEncoding.UTF_8_BOM).bytes();
        assertThat(bom).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

        byte[] utf16 = SheetWriter.delimited(List.of(), 1, viet, TextEncoding.UTF_16LE).bytes();
        assertThat(utf16).startsWith((byte) 0xFF, (byte) 0xFE);
        assertThat(new String(utf16, 2, utf16.length - 2, StandardCharsets.UTF_16LE)).isEqualTo("Áo thun đẹp\r\n");

        assertThatThrownBy(() -> SheetWriter.delimited(List.of(), 1, viet, TextEncoding.ISO_8859_1))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNENCODABLE))
                .hasMessageNotContaining("thun");
        byte[] latin = SheetWriter.delimited(List.of(), 1, List.of(List.of(SheetCell.text("café"))), TextEncoding.ISO_8859_1).bytes();
        assertThat(new String(latin, StandardCharsets.ISO_8859_1)).isEqualTo("café\r\n");
        assertThat(TextEncoding.parse("utf-8-bom")).contains(TextEncoding.UTF_8_BOM);
        assertThat(TextEncoding.parse("UTF_16LE")).contains(TextEncoding.UTF_16LE);
        assertThat(TextEncoding.parse("EBCDIC")).isEmpty();
    }

    @Test
    void theClipboardQuotesACellThatWouldBreakARowAndChangesNothing() {
        String text = SheetWriter.clipboard(List.of(List.of("item_sku", "item_name")),
                List.of(List.of(SheetCell.text("P-1"), SheetCell.text("two\nlines\tand \"quotes\"")), List.of(SheetCell.text("P-2"),
                        SheetCell.number(new BigDecimal("5.0")))));

        assertThat(text).isEqualTo("item_sku\titem_name\nP-1\t\"two\nlines\tand \"\"quotes\"\"\"\nP-2\t5\n");
        assertThat(SheetWriter.clipboard(List.of(), List.of())).isEmpty();
    }
}
