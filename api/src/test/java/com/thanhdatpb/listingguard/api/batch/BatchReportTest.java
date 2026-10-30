package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.thanhdatpb.listingguard.api.batch.ListingOutcome.Part;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckStatus;
import com.thanhdatpb.listingguard.api.check.FindingSource;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The report files, built from outcomes made by hand: layout, order, escaping, stability and the summary page. */
class BatchReportTest {

    private static final String DISCLAIMER = "Kết quả chỉ là cảnh báo tham khảo, không phải kết luận pháp lý.";
    private static final Instant RUN_1 = Instant.parse("2026-10-30T09:15:30Z");
    private static final Instant RUN_2 = Instant.parse("2026-11-02T17:45:10Z");

    @TempDir
    Path dir;

    // ---- builders

    private static CheckResult rules(UUID id, int errors, int warnings, CheckResult.FindingView... findings) {
        return new CheckResult(id, CheckStatus.COMPLETED, "ETSY", "etsy-test", "etsy-test", "hash", false, RUN_1,
                new CheckResult.Summary(errors, warnings, 0), List.of(findings));
    }

    private static CheckResult.FindingView ruleFinding(String ruleId, Severity severity, String field, String value) {
        return new CheckResult.FindingView(ruleId, FindingSource.RULE, severity, field, value, "Thông báo " + ruleId,
                "Gợi ý " + ruleId);
    }

    private static TrademarkCheckResult trademark(UUID id, RiskLevel risk, Completeness completeness,
                                                  TrademarkCheckResult.FindingView... findings) {
        return new TrademarkCheckResult(id, "ETSY", "trademark-test", "catalog-test", "analysis-test", false, completeness,
                risk, List.of(), DISCLAIMER, RUN_1, RUN_1.plusSeconds(3600), List.of(findings));
    }

    private static TrademarkCheckResult.FindingView markFinding(String mark, RiskLevel risk, String snippet,
                                                                String... sourceIds) {
        return new TrademarkCheckResult.FindingView("catalog_exact", risk, "title", 0, snippet.length(), snippet, mark,
                MatchType.EXACT, 1.0, false, List.of(), List.of(sourceIds), "Từ trùng nhãn hiệu", "Xem lại");
    }

    private static ListingOutcome outcome(int line, String id, Boolean label, CheckResult rules, TrademarkCheckResult tm) {
        return new ListingOutcome(line, id, label, null, Part.done(rules), Part.done(tm));
    }

    private BatchReport.RunInfo info(Instant at, String labelColumn, boolean trademarkReady, String abortReason) {
        return new BatchReport.RunInfo(at, "client-x", "ETSY", "export.csv", "mapping.yaml", labelColumn, null, false,
                abortReason, trademarkReady, trademarkReady ? null : "DISABLED: no catalog version is configured", DISCLAIMER);
    }

    private static List<Map<String, String>> csv(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        CsvMapper mapper = new CsvMapper();
        List<Map<String, String>> rows = new ArrayList<>();
        try (MappingIterator<Map<String, String>> it = mapper.readerFor(Map.class)
                .with(CsvSchema.emptySchema().withHeader()).readValues(text.substring(1))) {
            while (it.hasNext()) {
                rows.add(it.next());
            }
        }
        return rows;
    }

    /** A realistic mix: findings of both checks, a row that could not be read, and the two ways a check can be absent. */
    private void fill(BatchReport report) {
        report.add(outcome(2, "L-1", true,
                rules(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, 1,
                        ruleFinding("title_length", Severity.ERROR, "title", "Zorvex mug"),
                        ruleFinding("tag_length", Severity.WARNING, "tags[1]", "a tag")),
                trademark(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), RiskLevel.HIGH, Completeness.COMPLETE,
                        markFinding("Zorvex", RiskLevel.HIGH, "Zorvex", "S-001", "S-002"),
                        markFinding("Northvale", RiskLevel.MEDIUM, "Northvale", "S-009"))));
        report.add(ListingOutcome.unreadable(3, "L-2", "Dữ liệu sai: quantity = 'five' không phải số."));
        report.add(outcome(4, "L-3", false,
                rules(UUID.fromString("00000000-0000-0000-0000-000000000003"), 0, 0),
                trademark(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), RiskLevel.LOW, Completeness.COMPLETE)));
        report.add(new ListingOutcome(6, "L-4", null, null,
                Part.failed("DataAccessResourceFailureException: database is down"), Part.notConfigured("DISABLED")));
        report.add(outcome(7, "L-5", true,
                rules(UUID.fromString("00000000-0000-0000-0000-000000000005"), 0, 1,
                        ruleFinding("title_length", Severity.ERROR, "title", "Zorvex bag")),
                trademark(UUID.fromString("00000000-0000-0000-0000-0000000000a5"), RiskLevel.UNKNOWN, Completeness.INCOMPLETE,
                        markFinding("Zorvex", RiskLevel.LOW, "Zorvex", "S-001"))));
    }

    // ---- CSV cells

    @Test
    void aCellIsQuotedWhenItHoldsADelimiterAQuoteOrALineBreak() {
        assertThat(BatchReport.cell("plain")).isEqualTo("plain");
        assertThat(BatchReport.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(BatchReport.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(BatchReport.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(BatchReport.cell(null)).isEmpty();
        assertThat(BatchReport.cell("")).isEmpty();
    }

    @Test
    void aCellThatASpreadsheetWouldRunAsAFormulaIsDefused() {
        assertThat(BatchReport.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(BatchReport.cell("+1")).isEqualTo("'+1");
        assertThat(BatchReport.cell("-1")).isEqualTo("'-1");
        assertThat(BatchReport.cell("@SUM(1)")).isEqualTo("'@SUM(1)");
        assertThat(BatchReport.cell("\tx")).isEqualTo("'\tx");
        assertThat(BatchReport.cell("a=b")).isEqualTo("a=b");
    }

    @Test
    void aLongSnippetIsCutWithoutSplittingASurrogatePair() {
        String longText = "x".repeat(BatchReport.SNIPPET_MAX_CHARS + 30);
        assertThat(BatchReport.clip(longText)).isEqualTo("x".repeat(BatchReport.SNIPPET_MAX_CHARS) + "...");
        assertThat(BatchReport.clip("short")).isEqualTo("short");
        assertThat(BatchReport.clip(null)).isNull();

        String emoji = "😀";
        String atTheEdge = "x".repeat(BatchReport.SNIPPET_MAX_CHARS - 1) + emoji + "tail";
        assertThat(BatchReport.clip(atTheEdge)).isEqualTo("x".repeat(BatchReport.SNIPPET_MAX_CHARS - 1) + "...");
    }

    // ---- files

    @Test
    void findingsAndListingsAreWrittenInInputOrderWithTheDocumentedColumns() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            fill(report);
            report.finish(info(RUN_1, "takedown", true, null));
        }

        List<Map<String, String>> findings = csv(dir.resolve("findings.csv"));
        assertThat(findings).extracting(row -> row.get("id") + "/" + row.get("check") + "/" + row.get("ruleId"))
                .containsExactly("L-1/rules/title_length", "L-1/rules/tag_length", "L-1/trademark/catalog_exact",
                        "L-1/trademark/catalog_exact", "L-5/rules/title_length", "L-5/trademark/catalog_exact");
        Map<String, String> ruleRow = findings.get(0);
        assertThat(ruleRow).containsEntry("line", "2").containsEntry("severityRisk", "ERROR").containsEntry("field", "title")
                .containsEntry("snippet", "Zorvex mug").containsEntry("mark", "").containsEntry("sourceIds", "")
                .containsEntry("message", "Thông báo title_length").containsEntry("suggestion", "Gợi ý title_length");
        Map<String, String> markRow = findings.get(2);
        assertThat(markRow).containsEntry("severityRisk", "HIGH").containsEntry("snippet", "Zorvex")
                .containsEntry("mark", "Zorvex").containsEntry("sourceIds", "S-001|S-002");

        List<Map<String, String>> listings = csv(dir.resolve("listings.csv"));
        assertThat(listings).extracting(row -> row.get("id")).containsExactly("L-1", "L-2", "L-3", "L-4", "L-5");
        assertThat(listings.get(0)).containsEntry("line", "2").containsEntry("status", "OK").containsEntry("label", "true")
                .containsEntry("trademarkRisk", "HIGH").containsEntry("completeness", "COMPLETE")
                .containsEntry("ruleErrors", "1").containsEntry("ruleWarnings", "1")
                .containsEntry("rulesCheckId", "00000000-0000-0000-0000-000000000001")
                .containsEntry("trademarkCheckId", "00000000-0000-0000-0000-0000000000a1").containsEntry("error", "");
        assertThat(listings.get(1)).containsEntry("status", "INPUT_ERROR").containsEntry("trademarkRisk", "")
                .containsEntry("rulesCheckId", "").containsEntry("error", "Dữ liệu sai: quantity = 'five' không phải số.");
        assertThat(listings.get(2)).containsEntry("label", "false").containsEntry("trademarkRisk", "LOW");
        assertThat(listings.get(3)).containsEntry("status", "CHECK_ERROR").containsEntry("trademarkRisk", "NOT_CONFIGURED")
                .containsEntry("ruleErrors", "").containsEntry("label", "")
                .containsEntry("error", "rules: DataAccessResourceFailureException: database is down");
        assertThat(listings.get(4)).containsEntry("trademarkRisk", "UNKNOWN").containsEntry("completeness", "INCOMPLETE");
    }

    @Test
    void listingTextThatLooksLikeAFormulaIsDefusedInTheFiles() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            report.add(outcome(2, "=cmd|' /C calc'!A0", null,
                    rules(UUID.randomUUID(), 1, 0, ruleFinding("title_length", Severity.ERROR, "title", "=1+1")),
                    trademark(UUID.randomUUID(), RiskLevel.LOW, Completeness.COMPLETE)));
            report.finish(info(RUN_1, null, true, null));
        }

        assertThat(csv(dir.resolve("findings.csv")).get(0)).containsEntry("snippet", "'=1+1").containsEntry("id", "'=cmd|' /C calc'!A0");
        assertThat(csv(dir.resolve("listings.csv")).get(0)).containsEntry("id", "'=cmd|' /C calc'!A0");
    }

    @Test
    void aSnippetLongerThanTheLimitIsCutInTheFile() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            report.add(outcome(2, "L-1", null,
                    rules(UUID.randomUUID(), 1, 0, ruleFinding("title_length", Severity.ERROR, "title", "y".repeat(500))),
                    trademark(UUID.randomUUID(), RiskLevel.LOW, Completeness.COMPLETE)));
            report.finish(info(RUN_1, null, true, null));
        }

        assertThat(csv(dir.resolve("findings.csv")).get(0).get("snippet")).hasSize(BatchReport.SNIPPET_MAX_CHARS + 3).endsWith("...");
    }

    @Test
    void theDirectoryNeverMixesTwoRuns() throws IOException {
        Files.writeString(dir.resolve("summary.html"), "old run");
        Files.writeString(dir.resolve("findings.csv"), "old run");
        Files.writeString(dir.resolve("unrelated.txt"), "keep me");

        try (BatchReport report = new BatchReport(dir)) {
            // a run that stops here leaves no summary: its absence says the directory is unfinished
            assertThat(dir.resolve("summary.html")).doesNotExist();
            assertThat(Files.readString(dir.resolve("findings.csv"), StandardCharsets.UTF_8)).doesNotContain("old run");
            report.finish(info(RUN_1, null, true, null));
        }

        assertThat(Files.readString(dir.resolve("summary.html"), StandardCharsets.UTF_8)).doesNotContain("old run");
        assertThat(dir.resolve("unrelated.txt")).exists();
    }

    @Test
    void theSameOutcomesGiveTheSameFilesExceptTheRunTime() throws IOException {
        Path first = Files.createDirectory(dir.resolve("first"));
        Path second = Files.createDirectory(dir.resolve("second"));
        try (BatchReport report = new BatchReport(first)) {
            fill(report);
            report.finish(info(RUN_1, "takedown", true, null));
        }
        try (BatchReport report = new BatchReport(second)) {
            fill(report);
            report.finish(info(RUN_2, "takedown", true, null));
        }

        assertThat(Files.readAllBytes(second.resolve("findings.csv"))).isEqualTo(Files.readAllBytes(first.resolve("findings.csv")));
        assertThat(Files.readAllBytes(second.resolve("listings.csv"))).isEqualTo(Files.readAllBytes(first.resolve("listings.csv")));
        String firstHtml = Files.readString(first.resolve("summary.html"), StandardCharsets.UTF_8);
        String secondHtml = Files.readString(second.resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(firstHtml).isNotEqualTo(secondHtml).contains("2026-10-30 09:15:30 UTC");
        assertThat(secondHtml).contains("2026-11-02 17:45:10 UTC");
        assertThat(withoutRunTime(secondHtml)).isEqualTo(withoutRunTime(firstHtml));
    }

    private static String withoutRunTime(String html) {
        return html.replaceAll("<time datetime=\"[^\"]*\">[^<]*</time>", "<time/>");
    }

    // ---- summary.html

    @Test
    void theSummaryHasTheCountsTheTopsTheVersionsAndTheDisclaimer() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            fill(report);
            report.finish(info(RUN_1, null, true, null));
        }

        String html = Files.readString(dir.resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<html lang=\"vi\">").contains("client <b>client-x</b>").contains("<code>export.csv</code>")
                .contains("<code>mapping.yaml</code>")
                .contains("<td>Tổng số listing (dòng dữ liệu)</td><td class=\"n\">5</td>")
                .contains("<td>Dòng lỗi dữ liệu (không kiểm tra được, xem cuối trang)</td><td class=\"n\">1</td>")
                .contains("<td>Dòng có một check bị lỗi</td><td class=\"n\">1</td>")
                .contains("<td>HIGH (cao)</td><td class=\"n\">1</td>")
                .contains("<td>LOW (thấp)</td><td class=\"n\">1</td>")
                .contains("<td>UNKNOWN (chưa xác định, không phải sạch)</td><td class=\"n\">1</td>")
                .contains("<td>Trademark chưa cấu hình</td><td class=\"n\">1</td>")
                .contains("etsy-test").contains("trademark-test").contains("catalog-test").contains("analysis-test")
                .contains(DISCLAIMER);
        // Zorvex touches two listings (L-1 and L-5), Northvale one
        assertThat(html).containsSubsequence("<td>Zorvex</td><td class=\"n\">2</td><td class=\"n\">2</td><td>HIGH</td>",
                "<td>Northvale</td><td class=\"n\">1</td><td class=\"n\">1</td><td>MEDIUM</td>");
        // title_length is in two listings and is an ERROR in both, tag_length in one
        assertThat(html).containsSubsequence("<td>title_length</td><td class=\"n\">2</td><td class=\"n\">2</td><td>ERROR</td>",
                "<td>tag_length</td><td class=\"n\">1</td><td class=\"n\">1</td><td>WARNING</td>");
        // the unreadable row and the failed row are listed with their lines
        assertThat(html).contains("<td class=\"n\">3</td><td>L-2</td><td>Dữ liệu sai: quantity = &#39;five&#39; không phải số.</td>")
                .contains("<td class=\"n\">6</td><td>L-4</td><td>rules: DataAccessResourceFailureException: database is down</td>");
    }

    @Test
    void theSummaryIsOneSelfContainedPageAndEscapesTextFromTheListing() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            // listed among the rows with a problem, so its id appears in the page
            report.add(new ListingOutcome(2, "id<b>&\"", null, null, Part.failed("boom"),
                    Part.done(trademark(UUID.randomUUID(), RiskLevel.HIGH, Completeness.COMPLETE,
                            markFinding("<script>alert(1)</script>", RiskLevel.HIGH, "x")))));
            report.add(ListingOutcome.unreadable(3, "<img onerror=alert(1)>", "<script>boom</script>"));
            report.finish(info(RUN_1, "<label>", true, "the file <i>broke</i>"));
        }

        String html = Files.readString(dir.resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("id&lt;b&gt;&amp;&quot;")
                .contains("&lt;img onerror=alert(1)&gt;").contains("the file &lt;i&gt;broke&lt;/i&gt;");
        // the only tags are the ones the page writes itself, and nothing in it points outside the file
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<i>").doesNotContain("<link")
                .doesNotContain("<iframe").doesNotContain("<object").doesNotContain("<embed").doesNotContain("src=")
                .doesNotContain("href=").doesNotContain("url(").doesNotContain("@import").doesNotContain("http:")
                .doesNotContain("https:").doesNotContain("//");
        assertThat(html).startsWith("<!DOCTYPE html>").contains("<meta charset=\"utf-8\">");
    }

    @Test
    void anUnfinishedRunIsMarkedAsSuch() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            fill(report);
            report.finish(info(RUN_1, null, true, "the input file has bytes that are not valid UTF-8"));
        }

        assertThat(Files.readString(dir.resolve("summary.html"), StandardCharsets.UTF_8))
                .contains("Batch bị dừng giữa chừng, báo cáo này chưa đầy đủ").contains("not valid UTF-8");
    }

    @Test
    void whenTheCatalogIsNotServedTheSummarySaysTheTrademarkCheckWasNotConfigured() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            report.add(new ListingOutcome(2, "L-1", true, null,
                    Part.done(rules(UUID.randomUUID(), 0, 0)), Part.notConfigured("DISABLED")));
            report.finish(info(RUN_1, "takedown", false, null));
        }

        String html = Files.readString(dir.resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<b>Trademark chưa cấu hình</b>: DISABLED: no catalog version is configured")
                .contains("Phần rule vẫn được chạy")
                .contains("Không đo được: trademark chưa cấu hình");
        assertThat(csv(dir.resolve("listings.csv")).get(0)).containsEntry("trademarkRisk", "NOT_CONFIGURED")
                .containsEntry("status", "OK");
        // the disclaimer of the ruleset is shown even though no result carried one
        assertThat(html).contains(DISCLAIMER);
    }

    @Test
    void theMeasurementIsShownOnlyWhenThereIsALabelColumn() throws IOException {
        Path unlabeled = Files.createDirectory(dir.resolve("unlabeled"));
        Path labelled = Files.createDirectory(dir.resolve("labelled"));
        try (BatchReport report = new BatchReport(unlabeled)) {
            fill(report);
            report.finish(info(RUN_1, null, true, null));
        }
        try (BatchReport report = new BatchReport(labelled)) {
            fill(report);
            report.finish(info(RUN_1, "takedown", true, null));
        }

        assertThat(Files.readString(unlabeled.resolve("summary.html"), StandardCharsets.UTF_8)).doesNotContain("Đo lường");
        String html = Files.readString(labelled.resolve("summary.html"), StandardCharsets.UTF_8);
        // L-1 true+HIGH (hit), L-5 true+UNKNOWN, L-3 false+LOW; L-4 has no label; L-2 was not checked
        assertThat(html).contains("Đo lường theo nhãn <code>takedown</code>")
                .contains("<b>Tỷ lệ phát hiện</b> (nhãn true bị gắn cờ): 1/1 (100.0%)")
                .contains("<b>Tỷ lệ báo nhầm</b> (nhãn false bị gắn cờ): 0/1 (0.0%)")
                .contains("1 listing để trống nhãn, 1 dòng lỗi dữ liệu")
                .contains("không phải độ chính xác tổng quát").contains("2 listing nhãn true, 1 listing nhãn false");
    }

    @Test
    void thePageNeverCarriesAKeyOrASetting() throws IOException {
        try (BatchReport report = new BatchReport(dir)) {
            fill(report);
            report.finish(info(RUN_1, "takedown", true, null));
        }

        for (String name : List.of("summary.html", "findings.csv", "listings.csv")) {
            assertThat(Files.readString(dir.resolve(name), StandardCharsets.UTF_8).toLowerCase())
                    .as(name).doesNotContain("password").doesNotContain("api_key").doesNotContain("x-api-key")
                    .doesNotContain("jdbc:");
        }
    }
}
