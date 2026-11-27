package com.thanhdatpb.listingguard.workspace.sheets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

class SheetReaderTest {

    private static final SheetLimits LIMITS = SheetLimits.defaults();

    @Test
    void inspectListsEverySheetWithItsFirstRows() throws IOException {
        WorkbookInfo info = SheetReader.inspect(TestWorkbooks.flatFile(), LIMITS);

        assertThat(info.format()).isEqualTo(SheetFormat.XLSX);
        assertThat(info.macroProject()).isFalse();
        assertThat(info.sheets()).extracting(WorkbookInfo.SheetInfo::name).containsExactly("Instructions", "Template", "Valid Values");
        SheetGrid template = info.sheets().get(1).preview();
        assertThat(template.row(1)).containsExactly("TemplateType=synthetic", "Version=0.0", "Settings=none");
        assertThat(template.row(3)).containsExactlyElementsOf(TestWorkbooks.FIELDS);
    }

    @Test
    void readReturnsTheRowsOfTheNamedSheetAndKeepsRowNumbers() throws IOException {
        SheetGrid grid = SheetReader.read(TestWorkbooks.flatFile(), "Template", LIMITS);

        assertThat(grid.sheetName()).isEqualTo("Template");
        assertThat(grid.rowCount()).isEqualTo(7);
        assertThat(grid.row(4).get(0)).isEqualTo("P-1");
        assertThat(grid.row(5)).contains("P-1-S", "child", "P-1");
        // blank cells in the middle stay blank cells; trailing blanks are dropped
        assertThat(grid.row(4)).startsWith("P-1", "SHIRT", "Synthetic shirt", "Nobrand", "parent", "");
    }

    @Test
    void anUnknownSheetIsRefused() throws IOException {
        assertThatThrownBy(() -> SheetReader.read(TestWorkbooks.flatFile(), "Nope", LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.SHEET_NOT_FOUND));
    }

    @Test
    void aFormulaIsReadAsItsSavedResultNeverRecalculated() throws IOException {
        SheetGrid grid = SheetReader.read(TestWorkbooks.flatFileWithFormula(), "Template", LIMITS);

        assertThat(grid.row(2)).containsExactly("99");
    }

    @Test
    void aMacroEnabledWorkbookIsReadAndItsMacroProjectIsNeverOpened() throws IOException {
        byte[] xlsm = TestWorkbooks.asMacroEnabled(TestWorkbooks.flatFile());

        WorkbookInfo info = SheetReader.inspect(xlsm, LIMITS);

        assertThat(info.format()).isEqualTo(SheetFormat.XLSM);
        assertThat(info.macroProject()).isTrue();
        assertThat(SheetReader.read(xlsm, "Template", LIMITS).row(5).get(0)).isEqualTo("P-1-S");
    }

    @Test
    void theFieldRowOfAFlatFileIsGuessedNotAssumed() throws IOException {
        HeaderGuess guess = HeaderGuess.of(SheetReader.read(TestWorkbooks.flatFile(), "Template", LIMITS));

        assertThat(guess.fieldRow()).isEqualTo(3);
        assertThat(guess.firstDataRow()).isEqualTo(4);
        assertThat(guess.confident()).isTrue();
    }

    @Test
    void aSheetWithNoFieldLikeRowFallsBackToTheFirstRowAndSaysItIsNotConfident() {
        HeaderGuess guess = HeaderGuess.of(List.of(List.of("Tieu de", "Mo ta"), List.of("a", "b")));

        assertThat(guess).isEqualTo(new HeaderGuess(1, 2, false));
        assertThat(HeaderGuess.of(List.of())).isEqualTo(new HeaderGuess(0, 1, false));
    }

    @Test
    void aFileOverTheSizeLimitIsRefused() throws IOException {
        byte[] file = TestWorkbooks.flatFile();

        assertThatThrownBy(() -> SheetReader.inspect(file, LIMITS.withMaxBytes(100)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void aSheetWithMoreRowsThanTheLimitIsRefusedWholeNeverCut() throws IOException {
        byte[] wide = TestWorkbooks.wide(3, 30);

        assertThatThrownBy(() -> SheetReader.read(wide, "Template", LIMITS.withMaxRows(10)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_ROWS));
        assertThat(SheetReader.read(wide, "Template", LIMITS.withMaxRows(30)).rowCount()).isEqualTo(30);
    }

    @Test
    void aSheetWiderThanTheLimitIsRefused() throws IOException {
        byte[] wide = TestWorkbooks.wide(40, 3);
        SheetLimits narrow = new SheetLimits(LIMITS.maxBytes(), LIMITS.maxRows(), 20, LIMITS.minInflateRatio(),
                LIMITS.maxEntrySize(), LIMITS.maxTextSize());

        assertThatThrownBy(() -> SheetReader.read(wide, "Template", narrow))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_COLUMNS));
    }

    @Test
    void aDamagedPackageIsRefused() {
        byte[] damaged = new byte[200];
        damaged[0] = 'P';
        damaged[1] = 'K';
        damaged[2] = 3;
        damaged[3] = 4;

        assertThatThrownBy(() -> SheetReader.inspect(damaged, LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT));
    }

    @Test
    void aTruncatedWorkbookIsRefused() throws IOException {
        byte[] file = TestWorkbooks.flatFile();
        byte[] truncated = java.util.Arrays.copyOf(file, file.length / 2);

        assertThatThrownBy(() -> SheetReader.read(truncated, "Template", LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT));
    }

    @Test
    void aPasswordProtectedWorkbookIsRefused() throws IOException {
        // what Excel writes for a password: an OLE container whose payload is the stream EncryptedPackage
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[] {1, 2, 3}), "EncryptedPackage");
            fs.createDocument(new ByteArrayInputStream(new byte[] {4, 5, 6}), "EncryptionInfo");
            fs.writeFilesystem(out);
        }

        assertThatThrownBy(() -> SheetReader.inspect(out.toByteArray(), LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.ENCRYPTED));
    }

    @Test
    void anOldBinaryWorkbookIsRefusedAsUnsupported() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[] {1, 2, 3}), "Workbook");
            fs.writeFilesystem(out);
        }

        assertThatThrownBy(() -> SheetReader.inspect(out.toByteArray(), LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void aZipBombIsRefusedByTheCompressionRatio() throws IOException {
        // 40 MB of one letter in a comment compresses about 1000:1, far below the 0.01 ratio
        byte[] bomb = TestWorkbooks.withSheetPadding(TestWorkbooks.flatFile(), TestWorkbooks.repeated(40 << 20, (byte) 'x'));
        assertThat(bomb.length).isLessThan(1 << 20);

        assertThatThrownBy(() -> SheetReader.read(bomb, "Template", LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.ZIP_BOMB));
        assertThatThrownBy(() -> SheetReader.inspect(bomb, LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.ZIP_BOMB));
    }

    @Test
    void anEntryThatExpandsPastTheEntryLimitIsRefusedEvenWithAHarmlessRatio() throws IOException {
        byte[] big = TestWorkbooks.withSheetPadding(TestWorkbooks.flatFile(), TestWorkbooks.noise(3 << 20));

        assertThatThrownBy(() -> SheetReader.read(big, "Template", LIMITS.withMaxEntrySize(1 << 20)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.ZIP_BOMB));
        // the same file is fine under the default limit: it reads (a large file goes through the temporary-file path)
        assertThat(SheetReader.read(big, "Template", LIMITS).row(5).get(0)).isEqualTo("P-1-S");
    }

    @Test
    void textTablesAreReadWithQuotesAndEmbeddedLineBreaks() {
        String tsv = "item_sku\titem_name\tnote\r\nA-1\t\"Mug, \"\"blue\"\"\"\t\"line1\nline2\"\r\nA-2\tPlain\t\r\n";

        WorkbookInfo info = SheetReader.inspect(tsv.getBytes(StandardCharsets.UTF_8), LIMITS);

        assertThat(info.format()).isEqualTo(SheetFormat.TSV);
        assertThat(info.sheets()).hasSize(1);
        SheetGrid grid = SheetReader.read(tsv.getBytes(StandardCharsets.UTF_8), "ignored", LIMITS);
        assertThat(grid.rows()).containsExactly(List.of("item_sku", "item_name", "note"), List.of("A-1", "Mug, \"blue\"", "line1\nline2"),
                List.of("A-2", "Plain"));
    }

    @Test
    void aCommaFileWithAByteOrderMarkIsCsv() {
        byte[] csv = ("﻿sku,title\nA-1,Mug\n").getBytes(StandardCharsets.UTF_8);

        assertThat(SheetReader.detect(csv, LIMITS)).isEqualTo(SheetFormat.CSV);
        assertThat(SheetReader.read(csv, "", LIMITS).rows()).containsExactly(List.of("sku", "title"), List.of("A-1", "Mug"));
    }

    @Test
    void utf16TextFromExcelUnicodeTextIsRead() {
        byte[] text = ("﻿sku\ttitle\nA-1\tTách\n").getBytes(StandardCharsets.UTF_16LE);

        assertThat(SheetReader.read(text, "", LIMITS).rows()).containsExactly(List.of("sku", "title"), List.of("A-1", "Tách"));
    }

    @Test
    void textThatIsNotUtf8IsRefusedRatherThanGuessed() {
        byte[] latin1 = "sku\ttitle\nA-1\tCafé\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> SheetReader.read(latin1, "", LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void binaryDataIsNotATextTable() {
        byte[] binary = new byte[] {1, 2, 0, 3, 4, 5};

        assertThatThrownBy(() -> SheetReader.inspect(binary, LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNSUPPORTED));
        assertThatThrownBy(() -> SheetReader.inspect(new byte[0], LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY));
    }

    @Test
    void aTextTableOverTheRowLimitIsRefusedWholeAndAnUnclosedQuoteIsCorrupt() {
        StringBuilder many = new StringBuilder("a\tb\n");
        for (int i = 0; i < 20; i++) {
            many.append("x").append(i).append("\ty\n");
        }

        assertThatThrownBy(() -> SheetReader.read(many.toString().getBytes(StandardCharsets.UTF_8), "", LIMITS.withMaxRows(10)))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_ROWS));
        assertThatThrownBy(() -> SheetReader.read("a\t\"b\n".getBytes(StandardCharsets.UTF_8), "", LIMITS))
                .isInstanceOfSatisfying(SheetRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.CORRUPT));
    }

    @Test
    void messagesNeverCarryCellText() throws IOException {
        // the message of a refusal is safe to log: it names a limit or a reason, never a value of the file
        byte[] file = TestWorkbooks.wide(2, 30);
        SheetRejected rejected = org.junit.jupiter.api.Assertions.assertThrows(SheetRejected.class,
                () -> SheetReader.read(file, "Template", LIMITS.withMaxRows(5)));

        assertThat(rejected.getMessage()).doesNotContain("v0_0");
    }
}
