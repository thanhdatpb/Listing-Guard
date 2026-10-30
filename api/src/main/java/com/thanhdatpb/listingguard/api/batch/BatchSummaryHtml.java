package com.thanhdatpb.listingguard.api.batch;

import com.thanhdatpb.listingguard.api.batch.BatchReport.ErrorRow;
import com.thanhdatpb.listingguard.api.batch.BatchReport.Ranked;
import com.thanhdatpb.listingguard.api.batch.BatchReport.RunInfo;
import com.thanhdatpb.listingguard.api.batch.BatchReport.Totals;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code summary.html}: one page for a manager, in Vietnamese. It is a single file with its style inline and no script,
 * no image, no font and no link: opening it, emailing it or archiving it needs nothing else, and it contacts nobody. All
 * text that came from a listing or a catalog (a mark, a rule id, a value) is escaped.
 */
final class BatchSummaryHtml {

    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final String STYLE = """
            body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;padding:24px;color:#1f2933;background:#f5f7fa;line-height:1.5}
            main{max-width:960px;margin:0 auto;background:#fff;padding:24px 32px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
            h1{font-size:1.6rem;margin:0 0 4px}
            h2{font-size:1.15rem;margin:28px 0 8px;border-bottom:1px solid #e4e7eb;padding-bottom:4px}
            p{margin:6px 0}
            .meta{color:#52606d;font-size:.9rem}
            table{border-collapse:collapse;width:100%;margin:8px 0;font-size:.92rem}
            th,td{border:1px solid #d9e2ec;padding:6px 10px;text-align:left;vertical-align:top}
            th{background:#f0f4f8}
            td.n,th.n{text-align:right;font-variant-numeric:tabular-nums}
            .banner{padding:10px 14px;border-radius:6px;margin:12px 0}
            .warn{background:#fff3c4;border:1px solid #f7c948}
            .stop{background:#ffe3e3;border:1px solid #e12d39}
            .note{color:#52606d;font-size:.88rem}
            code{background:#f0f4f8;padding:1px 5px;border-radius:3px;font-size:.88em}
            """;

    private BatchSummaryHtml() {
    }

    static String render(Totals t, RunInfo info) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Listing Guard - Báo cáo kiểm tra hàng loạt</title>\n<style>\n").append(STYLE)
                .append("</style>\n</head>\n<body>\n<main>\n");
        html.append("<h1>Báo cáo kiểm tra hàng loạt</h1>\n");
        html.append("<p class=\"meta\">Chạy lúc <time datetime=\"").append(esc(info.runAt().toString())).append("\">")
                .append(esc(RUN_TIME.format(info.runAt()))).append("</time> · client <b>").append(esc(info.client()))
                .append("</b> · sàn <b>").append(esc(info.marketplace())).append("</b> · file vào <code>")
                .append(esc(info.inputName())).append("</code> · mapping <code>").append(esc(info.mappingName()))
                .append("</code></p>\n");
        banners(html, t, info);
        overview(html, t);
        trademark(html, t);
        rules(html, t);
        topTable(html, "Mark bị khớp nhiều nhất", "Mark", t.topMarks(), t.distinctMarks(), "Mức cao nhất",
                "Không có mark nào bị khớp.");
        topTable(html, "Rule bị vi phạm nhiều nhất", "Rule", t.topRules(), t.distinctRules(), "Mức cao nhất",
                "Không có rule nào bị vi phạm.");
        if (info.labelColumn() != null) {
            measurement(html, t, info);
        }
        errorRows(html, t);
        versions(html, t);
        notes(html, t);
        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void banners(StringBuilder html, Totals t, RunInfo info) {
        if (info.abortReason() != null) {
            html.append("<div class=\"banner stop\"><b>Batch bị dừng giữa chừng, báo cáo này chưa đầy đủ:</b> ")
                    .append(esc(info.abortReason())).append("</div>\n");
        }
        if (info.stoppedAtMaxRows()) {
            html.append("<div class=\"banner warn\">Chỉ kiểm tra <b>").append(info.maxRows())
                    .append("</b> dòng dữ liệu đầu tiên (--max-rows); file còn nhiều dòng hơn.</div>\n");
        }
        if (!info.trademarkReady()) {
            html.append("<div class=\"banner warn\"><b>Trademark chưa cấu hình</b>: ").append(esc(info.trademarkDetail()))
                    .append(". Phần rule vẫn được chạy; mọi kết quả trademark bên dưới là trống.</div>\n");
        }
    }

    private static void overview(StringBuilder html, Totals t) {
        html.append("<h2>Tổng quan</h2>\n<table>\n");
        row(html, "Tổng số listing (dòng dữ liệu)", t.rows());
        row(html, "Kiểm tra được", t.rows() - t.unreadable());
        row(html, "Dòng lỗi dữ liệu (không kiểm tra được, xem cuối trang)", t.unreadable());
        row(html, "Dòng có một check bị lỗi", t.failed());
        html.append("</table>\n");
    }

    private static void trademark(StringBuilder html, Totals t) {
        html.append("<h2>Trademark: phân bố theo mức rủi ro (riskLevel)</h2>\n<table>\n")
                .append("<tr><th>Mức</th><th class=\"n\">Số listing</th><th class=\"n\">Tỷ lệ</th></tr>\n");
        Map<String, Integer> distribution = t.trademarkRisk();
        for (String level : List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")) {
            share(html, levelLabel(level), distribution.getOrDefault(level, 0), t.rows());
        }
        for (String other : List.of(BatchReport.Risk.NOT_CONFIGURED, BatchReport.Risk.FAILED, BatchReport.Risk.NOT_CHECKED)) {
            int count = distribution.getOrDefault(other, 0);
            if (count > 0) {
                share(html, levelLabel(other), count, t.rows());
            }
        }
        html.append("</table>\n<p class=\"note\">Kết quả đầy đủ (COMPLETE): ").append(t.complete())
                .append(" · chưa đầy đủ (INCOMPLETE): ").append(t.incomplete())
                .append(". UNKNOWN nghĩa là chưa xác định được rủi ro, <b>không</b> có nghĩa là sạch.</p>\n");
    }

    private static void rules(StringBuilder html, Totals t) {
        html.append("<h2>Rule</h2>\n<table>\n");
        row(html, "Listing có ít nhất một ERROR", t.listingsWithRuleErrors());
        row(html, "Listing có ít nhất một WARNING", t.listingsWithRuleWarnings());
        row(html, "Tổng số ERROR", t.ruleErrors());
        row(html, "Tổng số WARNING", t.ruleWarnings());
        row(html, "Tổng số INFO", t.ruleInfos());
        html.append("</table>\n");
    }

    private static void topTable(StringBuilder html, String title, String nameHeader, List<Ranked> ranked, int distinct,
                                 String highestHeader, String empty) {
        html.append("<h2>").append(esc(title)).append("</h2>\n");
        if (ranked.isEmpty()) {
            html.append("<p>").append(esc(empty)).append("</p>\n");
            return;
        }
        html.append("<table>\n<tr><th>").append(esc(nameHeader)).append("</th><th class=\"n\">Số listing</th>")
                .append("<th class=\"n\">Số finding</th><th>").append(esc(highestHeader)).append("</th></tr>\n");
        for (Ranked item : ranked) {
            html.append("<tr><td>").append(esc(item.name())).append("</td><td class=\"n\">").append(item.listings())
                    .append("</td><td class=\"n\">").append(item.findings()).append("</td><td>")
                    .append(esc(item.highest())).append("</td></tr>\n");
        }
        html.append("</table>\n<p class=\"note\">").append(ranked.size()).append(" trên ").append(distinct)
                .append(" mục khác nhau, xếp theo số listing rồi số finding.</p>\n");
    }

    private static void measurement(StringBuilder html, Totals t, RunInfo info) {
        Measurement m = t.measurement();
        html.append("<h2>Đo lường theo nhãn <code>").append(esc(info.labelColumn())).append("</code></h2>\n");
        if (!info.trademarkReady()) {
            html.append("<p>Không đo được: trademark chưa cấu hình, nên không có listing nào được gắn cờ hay bỏ sót.</p>\n");
            return;
        }
        html.append("<p class=\"note\">Gắn cờ = trademark riskLevel là MEDIUM hoặc HIGH. \"Không gắn cờ\" = LOW. "
                + "Listing chưa xác định được rủi ro (UNKNOWN, check lỗi) được đếm riêng và không tính vào tỷ lệ.</p>\n");
        html.append("<table>\n<tr><th>Nhãn</th><th class=\"n\">Gắn cờ</th><th class=\"n\">Không gắn cờ</th>"
                + "<th class=\"n\">Chưa xác định</th></tr>\n");
        html.append("<tr><td>true (ví dụ: đã bị gỡ)</td><td class=\"n\">").append(m.truePositive())
                .append("</td><td class=\"n\">").append(m.falseNegative()).append(" (bỏ sót)</td><td class=\"n\">")
                .append(m.trueUndetermined()).append("</td></tr>\n");
        html.append("<tr><td>false</td><td class=\"n\">").append(m.falsePositive()).append(" (báo nhầm)</td><td class=\"n\">")
                .append(m.trueNegative()).append("</td><td class=\"n\">").append(m.falseUndetermined())
                .append("</td></tr>\n</table>\n");
        html.append("<p><b>Tỷ lệ phát hiện</b> (nhãn true bị gắn cờ): ").append(esc(m.detectionRate().text()))
                .append("<br><b>Tỷ lệ báo nhầm</b> (nhãn false bị gắn cờ): ").append(esc(m.falseAlarmRate().text()))
                .append("</p>\n");
        html.append("<p class=\"note\">Không đo: ").append(m.unlabeled()).append(" listing để trống nhãn, ")
                .append(m.notChecked()).append(" dòng lỗi dữ liệu.</p>\n");
        html.append("<div class=\"banner warn\">Đây là số đo trên một tập nhỏ (").append(m.labelledTrue())
                .append(" listing nhãn true, ").append(m.labelledFalse())
                .append(" listing nhãn false), không phải độ chính xác tổng quát: tập khác, hay catalog khác, có thể cho "
                        + "kết quả rất khác. Catalog chỉ phủ những nhãn hiệu có trong nó, nên một listing bị gỡ vì lý do "
                        + "khác hoặc vì nhãn hiệu không có trong catalog sẽ tính là bỏ sót.</div>\n");
    }

    private static void errorRows(StringBuilder html, Totals t) {
        html.append("<h2>Dòng có vấn đề</h2>\n");
        if (t.errorRows().isEmpty()) {
            html.append("<p>Không có.</p>\n");
            return;
        }
        html.append("<table>\n<tr><th class=\"n\">Dòng</th><th>id</th><th>Vấn đề</th></tr>\n");
        for (ErrorRow row : t.errorRows()) {
            html.append("<tr><td class=\"n\">").append(row.line()).append("</td><td>").append(esc(row.id()))
                    .append("</td><td>").append(esc(row.message())).append("</td></tr>\n");
        }
        html.append("</table>\n");
        if (t.errorRowsTotal() > t.errorRows().size()) {
            html.append("<p class=\"note\">Và ").append(t.errorRowsTotal() - t.errorRows().size())
                    .append(" dòng khác; danh sách đầy đủ nằm trong <code>listings.csv</code>.</p>\n");
        }
    }

    private static void versions(StringBuilder html, Totals t) {
        html.append("<h2>Phiên bản</h2>\n<table>\n");
        versionRow(html, "Ruleset (rule)", t.rulesetVersions());
        versionRow(html, "analysisVersion (rule)", t.ruleAnalysisVersions());
        versionRow(html, "Ruleset (trademark)", t.trademarkRulesetVersions());
        versionRow(html, "Catalog trademark", t.catalogVersions());
        versionRow(html, "analysisVersion (trademark)", t.trademarkAnalysisVersions());
        html.append("</table>\n<p class=\"note\">Kết quả đã lưu của client được dùng lại khi cùng nội dung và cùng analysisVersion, "
                + "nên một lần chạy lại cho cùng checkId.</p>\n");
    }

    private static void notes(StringBuilder html, Totals t) {
        html.append("<h2>Lưu ý</h2>\n");
        if (t.disclaimer() != null) {
            html.append("<p>").append(esc(t.disclaimer())).append("</p>\n");
        }
        html.append("<p class=\"note\">Listing Guard chỉ kiểm tra và gợi ý. Nó không sửa listing, không đăng và không làm SEO. "
                + "Chi tiết từng finding nằm trong <code>findings.csv</code>, từng listing trong <code>listings.csv</code>.</p>\n");
    }

    // ---- pieces

    private static void row(StringBuilder html, String label, int value) {
        html.append("<tr><td>").append(esc(label)).append("</td><td class=\"n\">").append(value).append("</td></tr>\n");
    }

    private static void share(StringBuilder html, String label, int count, int total) {
        String percent = total == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * count / total);
        html.append("<tr><td>").append(esc(label)).append("</td><td class=\"n\">").append(count)
                .append("</td><td class=\"n\">").append(percent).append("</td></tr>\n");
    }

    private static void versionRow(StringBuilder html, String label, Set<String> values) {
        html.append("<tr><td>").append(esc(label)).append("</td><td>")
                .append(values.isEmpty() ? "-" : esc(String.join(", ", new java.util.TreeSet<>(values)))).append("</td></tr>\n");
    }

    private static String levelLabel(String level) {
        return switch (level) {
            case "HIGH" -> "HIGH (cao)";
            case "MEDIUM" -> "MEDIUM (trung bình)";
            case "LOW" -> "LOW (thấp)";
            case "UNKNOWN" -> "UNKNOWN (chưa xác định, không phải sạch)";
            case BatchReport.Risk.NOT_CONFIGURED -> "Trademark chưa cấu hình";
            case BatchReport.Risk.FAILED -> "Check trademark bị lỗi";
            default -> "Không kiểm tra (dòng lỗi dữ liệu)";
        };
    }

    /** HTML-escapes text that came from outside. */
    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
