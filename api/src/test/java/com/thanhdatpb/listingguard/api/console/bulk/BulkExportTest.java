package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Row;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The block pasted back into a Sheet and the CSV file (ADR-029). Synthetic rows only. */
class BulkExportTest {
    private static final ConsoleMessages MESSAGES = BulkTestMessages.vi();


    private static Row row(int index, int line, String sku, Status status, int findings, String summary) {
        return new Row(index, line, sku, status, status.label(MESSAGES), findings, null, summary, null);
    }

    private static final List<Row> ROWS = List.of(
            row(1, 2, "S-1", Status.OK, 0, "Không có phát hiện."),
            row(2, 3, null, Status.EMPTY, 0, ""),
            row(3, 4, "S-3", Status.WARNING, 2, "2 phát hiện. Tag quá dài, cần rút"),
            row(4, 5, "S-4", Status.UNREADABLE, 0, "Không đọc được: Dữ liệu sai: giá"),
            row(5, 6, "S-5", Status.ERROR, 1, "1 phát hiện. Thiếu mô tả"));

    @Test
    void theBlockHasOneLineForEveryRowInOrderAndABlankRowIsABlankLine() {
        String[] lines = BulkExport.sheetBlock(MESSAGES, ROWS, false).split("\n", -1);

        assertThat(lines).hasSize(ROWS.size());
        assertThat(lines[0]).isEqualTo("Đạt\tKhông có phát hiện.");
        assertThat(lines[1]).as("the blank row keeps its place").isEqualTo("\t");
        assertThat(lines[2]).startsWith("Cảnh báo\t2 phát hiện.");
        assertThat(lines[3]).startsWith("Không đọc được\t");
        assertThat(lines[4]).startsWith("Lỗi\t1 phát hiện.");
    }

    @Test
    void aTableThatHadAHeaderGetsAHeaderLineSoTheBlockPastesBesideTheHeaderRow() {
        String[] lines = BulkExport.sheetBlock(MESSAGES, ROWS, true).split("\n", -1);

        assertThat(lines).hasSize(ROWS.size() + 1);
        assertThat(lines[0]).isEqualTo("Trạng thái\tTóm tắt");
        assertThat(lines[1]).startsWith("Đạt\t");
    }

    @Test
    void everyLineOfTheBlockHasExactlyTwoCellsEvenWhenATextHasATabOrALineBreak() {
        List<Row> rows = List.of(row(1, 2, "S-1", Status.WARNING, 1, BulkAssessment.summary("a\tb\nc")));

        String block = BulkExport.sheetBlock(MESSAGES, rows, false);

        assertThat(block.split("\n", -1)).hasSize(1);
        assertThat(block.split("\t", -1)).hasSize(2);
    }

    @Test
    void aCellThatLooksLikeAFormulaGetsALeadingApostrophe() {
        assertThat(BulkExport.defuse("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(BulkExport.defuse("+1")).isEqualTo("'+1");
        assertThat(BulkExport.defuse("-2")).isEqualTo("'-2");
        assertThat(BulkExport.defuse("@x")).isEqualTo("'@x");
        assertThat(BulkExport.defuse("1 phát hiện")).isEqualTo("1 phát hiện");
        assertThat(BulkExport.defuse(null)).isEmpty();

        List<Row> rows = List.of(row(1, 2, "=HYPERLINK(\"x\")", Status.WARNING, 1, "=cmd|' /C calc'!A0"));
        assertThat(BulkExport.sheetBlock(MESSAGES, rows, false)).endsWith("\t'=cmd|' /C calc'!A0");
        assertThat(BulkExport.csv(MESSAGES, rows)).contains("'=HYPERLINK").contains("'=cmd");
    }

    @Test
    void theCsvIsUtf8WithAByteOrderMarkAndQuotesWhatNeedsIt() {
        List<Row> rows = List.of(row(1, 2, "S,1", Status.WARNING, 1, "Một \"phát hiện\", hai"));

        String csv = BulkExport.csv(MESSAGES, rows);

        assertThat(csv).startsWith("﻿Dòng,SKU,Trạng thái,Số phát hiện,Tóm tắt\r\n");
        assertThat(csv).endsWith("2,\"S,1\",Cảnh báo,1,\"Một \"\"phát hiện\"\", hai\"\r\n");
    }

    @Test
    void aBlankRowIsInTheCsvToo() {
        String csv = BulkExport.csv(MESSAGES, ROWS);

        assertThat(csv.split("\r\n")).hasSize(ROWS.size() + 1);
        assertThat(csv).contains("\r\n3,,Dòng trống,0,\r\n");
    }

    @Test
    void theDownloadLinkCarriesTheSameCsvWithoutTheServerKeepingIt() {
        String csv = BulkExport.csv(MESSAGES, ROWS);

        String href = BulkExport.csvHref(csv);

        assertThat(href).startsWith("data:text/csv;charset=utf-8;base64,");
        byte[] bytes = Base64.getDecoder().decode(href.substring(href.indexOf(',') + 1));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(csv);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
    }

    @Test
    void oneLineCollapsesBlanksTabsAndLineBreaks() {
        assertThat(BulkExport.oneLine("  a \t b\r\nc\n\nd  ")).isEqualTo("a b c d");
        assertThat(BulkExport.oneLine(null)).isEmpty();
    }
}
