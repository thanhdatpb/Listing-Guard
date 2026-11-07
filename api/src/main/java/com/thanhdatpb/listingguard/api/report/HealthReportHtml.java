package com.thanhdatpb.listingguard.api.report;

import com.thanhdatpb.listingguard.api.report.HealthReport.CoverageView;
import com.thanhdatpb.listingguard.api.report.HealthReport.ListingRulesView;
import com.thanhdatpb.listingguard.api.report.HealthReport.MarkRankingView;
import com.thanhdatpb.listingguard.api.report.HealthReport.PersonalizationView;
import com.thanhdatpb.listingguard.api.report.HealthReport.RuleRankingView;
import com.thanhdatpb.listingguard.api.report.HealthReport.SeverityCountView;
import com.thanhdatpb.listingguard.api.report.HealthReport.TrademarkRiskView;
import com.thanhdatpb.listingguard.api.report.HealthReport.VariationChecksView;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code report.html} of the {@code health-report} job (ADR-018): one page for a lead, in Vietnamese, with a section for each
 * client. It is a single file with its style inline and **no script, no link, no image, no font and no external address**, and a
 * {@code Content-Security-Policy} meta that allows nothing else, so opening it, mailing it or archiving it needs nothing and
 * contacts nobody. Every cell goes through {@link #esc}: a rule id or a catalog mark that holds markup comes out as text.
 *
 * <p>It shows the numbers of {@link HealthReport} and no other: what the page says about trademark results is the ruleset's own
 * disclaimer, handed in by the job.
 */
final class HealthReportHtml {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final String STYLE = """
            body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;padding:24px;color:#1f2933;background:#f5f7fa;line-height:1.5}
            main{max-width:1100px;margin:0 auto}
            section{background:#fff;padding:20px 28px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12);margin:0 0 24px}
            h1{font-size:1.6rem;margin:0 0 4px}
            h2{font-size:1.3rem;margin:0 0 8px}
            h3{font-size:1.05rem;margin:22px 0 6px;border-bottom:1px solid #e4e7eb;padding-bottom:4px}
            p{margin:6px 0}
            .meta{color:#52606d;font-size:.9rem}
            table{border-collapse:collapse;width:100%;margin:8px 0;font-size:.9rem}
            th,td{border:1px solid #d9e2ec;padding:5px 9px;text-align:left;vertical-align:top}
            th{background:#f0f4f8}
            td.n,th.n{text-align:right;font-variant-numeric:tabular-nums}
            .banner{padding:10px 14px;border-radius:6px;margin:12px 0}
            .warn{background:#fff3c4;border:1px solid #f7c948}
            .ok{background:#e3f9e5;border:1px solid #7bc47f}
            .note{color:#52606d;font-size:.86rem}
            code{background:#f0f4f8;padding:1px 5px;border-radius:3px;font-size:.88em}
            @media (max-width:700px){body{padding:8px}section{padding:12px}table{display:block;overflow-x:auto}}
            """;

    private HealthReportHtml() {
    }

    static String render(HealthReportJob.RunFile run, String trademarkDisclaimer) {
        StringBuilder html = new StringBuilder(32768);
        html.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Listing Guard - Báo cáo sức khỏe listing</title>\n<style>\n").append(STYLE)
                .append("</style>\n</head>\n<body>\n<main>\n");
        html.append("<section>\n<h1>Báo cáo sức khỏe listing</h1>\n<p class=\"meta\">Tạo lúc <time datetime=\"")
                .append(esc(run.generatedAt().toString())).append("\">").append(esc(TIME.format(run.generatedAt())))
                .append("</time> · khoảng ngày <b>").append(esc(run.period().from().toString())).append("</b> đến <b>")
                .append(esc(run.period().to().toString())).append("</b> (").append(run.period().days()).append(" ngày, UTC) · sàn <b>")
                .append(esc(run.marketplace() == null ? "tất cả" : run.marketplace())).append("</b> · ")
                .append(run.clients().size()).append(" client</p>\n")
                .append("<p class=\"note\">Đây là số <b>kết quả đã lưu</b> của từng client, không phải số lượt gọi: một listing kiểm lại với cùng "
                        + "phiên bản phân tích được trả từ cache và chỉ tính một lần, nên báo cáo không có số lượt cache. Báo cáo chỉ đếm; "
                        + "nó không chứa nội dung listing hay chữ khách hàng đã nhập.</p>\n</section>\n");
        for (HealthReportJob.ClientEntry entry : run.clients()) {
            client(html, entry);
        }
        html.append("<section>\n<h2>Lưu ý</h2>\n");
        if (trademarkDisclaimer != null && !trademarkDisclaimer.isBlank()) {
            html.append("<p>").append(esc(trademarkDisclaimer)).append("</p>\n");
        }
        html.append("<p class=\"note\">Listing Guard chỉ kiểm tra và gợi ý. Nó không sửa listing, không đăng và không làm SEO. "
                + "UNKNOWN nghĩa là chưa xác định được rủi ro, <b>không</b> có nghĩa là sạch.</p>\n</section>\n");
        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void client(StringBuilder html, HealthReportJob.ClientEntry entry) {
        HealthReport r = entry.report();
        html.append("<section>\n<h2>Client <b>").append(esc(entry.client())).append("</b></h2>\n");
        coverage(html, r.coverageNote(), r.period());
        overview(html, r);
        listing(html, r.packs().listingRules());
        trademark(html, r.packs().trademarkRisk());
        variation(html, r.packs().variationChecks());
        personalization(html, r.packs().personalizationScreening());
        weeks(html, r.byWeek());
        versions(html, r);
        html.append("</section>\n");
    }

    private static void coverage(StringBuilder html, CoverageView note, HealthReport.PeriodView period) {
        List<HealthReport.RetentionView> incomplete = note.retention().stream()
                .filter(retention -> period.from().isBefore(retention.coveredFrom())).toList();
        if (incomplete.isEmpty()) {
            html.append("<div class=\"banner ok\">Mọi pack đều còn đủ số liệu cho cả khoảng ngày. Kết quả của ")
                    .append(esc(note.retention().stream().map(r -> r.pack() + " (lưu " + r.period() + ")")
                            .collect(Collectors.joining(", "))))
                    .append(" bị xóa sau thời hạn lưu trong ngoặc; kết quả listing-rules không bị xóa.</div>\n");
            return;
        }
        html.append("<div class=\"banner warn\"><b>Số liệu chưa đủ cho cả khoảng ngày.</b> Khoảng ngày bắt đầu trước thời hạn lưu của: ")
                .append(esc(incomplete.stream().map(r -> r.pack() + " (lưu " + r.period() + ", đủ số liệu từ ngày " + r.coveredFrom() + ")")
                        .collect(Collectors.joining(", "))))
                .append(". Số của các pack này cho những ngày trước đó bị thiếu vì kết quả đã bị xóa theo thời hạn lưu (cấu hình của "
                        + "triển khai). Kết quả listing-rules không bị xóa nên đầy đủ cho cả khoảng.</div>\n");
    }

    private static void overview(StringBuilder html, HealthReport r) {
        html.append("<h3>Tổng quan</h3>\n<table>\n");
        header(html, "Pack", "Kết quả đã lưu", "Trạng thái");
        row(html, "listing-rules", r.packs().listingRules().checks(), pairs(r.packs().listingRules().statuses()));
        row(html, "trademark-risk", r.packs().trademarkRisk().checks(), pairs(r.packs().trademarkRisk().completeness()));
        row(html, "variation-checks", r.packs().variationChecks().checks(), pairs(r.packs().variationChecks().completeness()));
        row(html, "personalization-screening", r.packs().personalizationScreening().checks(),
                pairs(r.packs().personalizationScreening().completeness()));
        html.append("</table>\n");
    }

    private static void listing(StringBuilder html, ListingRulesView v) {
        html.append("<h3>listing-rules: finding theo mức</h3>\n");
        severities(html, v.findings());
        HealthReport.ReadinessSummaryView readiness = v.readiness();
        html.append("<h3>listing-rules: điểm sẵn sàng đã lưu</h3>\n<table>\n");
        row(html, "Kết quả có điểm sẵn sàng", readiness.checksWithReadiness());
        row(html, "Trong đó có điểm", readiness.scoredChecks());
        row(html, "Trong đó INCOMPLETE (không có điểm, tính là chưa sẵn sàng)", readiness.incompleteChecks());
        row(html, "Sẵn sàng", readiness.readyChecks());
        row(html, "Tỷ lệ sẵn sàng", readiness.readyRate() == null ? "-" : percent(readiness.readyRate()));
        row(html, "Điểm trung bình (chỉ kết quả có điểm)",
                readiness.averageScore() == null ? "-" : String.format(Locale.ROOT, "%.1f", readiness.averageScore()));
        row(html, "Kết quả không lưu điểm sẵn sàng (bị bỏ qua, không tính là 0)", readiness.checksWithoutReadiness());
        row(html, "Phiên bản công thức", readiness.versions() == null || readiness.versions().isEmpty() ? "-"
                : String.join(", ", readiness.versions()));
        html.append("</table>\n<p class=\"note\">Điểm chỉ tính kết quả listing-rules, không gồm trademark. Điểm chỉ có khi tính năng gợi ý "
                + "sửa được bật hoặc kết quả đến từ trình soạn trong console.</p>\n");
        rules(html, "listing-rules: rule có nhiều finding nhất", v.topRules());
    }

    private static void trademark(StringBuilder html, TrademarkRiskView v) {
        html.append("<h3>trademark-risk: phân bố theo mức rủi ro</h3>\n<table>\n");
        header(html, "Mức", "Kết quả", "Tỷ lệ");
        for (String level : List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")) {
            long count = v.riskLevels().getOrDefault(level, 0L);
            row(html, switch (level) {
                case "HIGH" -> "HIGH (cao)";
                case "MEDIUM" -> "MEDIUM (trung bình)";
                case "LOW" -> "LOW (thấp)";
                default -> "UNKNOWN (chưa xác định, không phải sạch)";
            }, count, v.checks() == 0 ? "-" : percent((double) count / v.checks()));
        }
        html.append("</table>\n<p class=\"note\">Kết quả đầy đủ/chưa đầy đủ: ").append(esc(pairs(v.completeness())))
                .append(". Chỉ tính kết quả còn được lưu.</p>\n");
        marks(html, v.topMarks());
    }

    private static void variation(StringBuilder html, VariationChecksView v) {
        html.append("<h3>variation-checks: finding theo mức</h3>\n");
        severities(html, v.findings());
        rules(html, "variation-checks: rule có nhiều finding nhất", v.topRules());
    }

    private static void personalization(StringBuilder html, PersonalizationView v) {
        html.append("<h3>personalization-screening</h3>\n<table>\n");
        row(html, "Trạng thái quét trademark", pairs(v.trademarkStatus()));
        row(html, "Mức rủi ro trademark", pairs(v.trademarkRiskLevels()));
        html.append("</table>\n<p class=\"note\">Không liệt kê mark nào: một mark khớp với chữ khách hàng nhập cho biết điều gì đó về chữ "
                + "đó, là dữ liệu cá nhân.</p>\n");
        html.append("<h3>personalization-screening: finding của luật ký tự theo mức</h3>\n");
        severities(html, v.findings());
        rules(html, "personalization-screening: luật ký tự có nhiều finding nhất", v.topRules());
    }

    private static void weeks(StringBuilder html, List<HealthReport.WeekView> weeks) {
        html.append("<h3>Theo tuần ISO</h3>\n<table>\n");
        header(html, "Tuần", "Từ", "Đến", "Một phần", "Rule: kết quả", "ERROR", "WARNING", "Sẵn sàng", "Điểm TB", "Trademark: kết quả",
                "HIGH", "MEDIUM", "LOW", "UNKNOWN", "Variation: kết quả", "Personalization: kết quả");
        for (HealthReport.WeekView week : weeks) {
            HealthReport.ReadinessSummaryView readiness = week.listingRules().readiness();
            row(html, week.isoWeek(), week.from().toString(), week.to().toString(), week.partial() ? "có" : "không",
                    week.listingRules().checks(), week.listingRules().findings().get(0).findings(),
                    week.listingRules().findings().get(1).findings(),
                    readiness.readyRate() == null ? "-" : percent(readiness.readyRate()),
                    readiness.averageScore() == null ? "-" : String.format(Locale.ROOT, "%.1f", readiness.averageScore()),
                    week.trademarkRisk().checks(), week.trademarkRisk().riskLevels().getOrDefault("HIGH", 0L),
                    week.trademarkRisk().riskLevels().getOrDefault("MEDIUM", 0L), week.trademarkRisk().riskLevels().getOrDefault("LOW", 0L),
                    week.trademarkRisk().riskLevels().getOrDefault("UNKNOWN", 0L), week.variationChecks().checks(),
                    week.personalizationScreening().checks());
        }
        html.append("</table>\n<p class=\"note\">Tuần \"một phần\" nằm ngoài khoảng ngày ở một đầu nên có ít kết quả hơn: so sánh tỷ lệ và "
                + "điểm, không so sánh số đếm.</p>\n");
    }

    private static void versions(StringBuilder html, HealthReport r) {
        html.append("<h3>Phiên bản đang chạy</h3>\n<table>\n");
        header(html, "Pack", "Sàn", "Phiên bản ruleset");
        for (HealthReport.RulesetView ruleset : r.versions().rulesets()) {
            row(html, ruleset.pack(), ruleset.marketplace(), ruleset.version());
        }
        html.append("</table>\n<p class=\"note\">Catalog trademark: ").append(esc(r.versions().trademarkCatalog().state()))
                .append(r.versions().trademarkCatalog().version() == null ? "" : " (" + esc(r.versions().trademarkCatalog().version()) + ")")
                .append(". Kết quả của khoảng ngày có thể được tạo với phiên bản cũ hơn.</p>\n");
    }

    // ---- pieces

    private static void severities(StringBuilder html, List<SeverityCountView> severities) {
        html.append("<table>\n");
        header(html, "Mức", "Số finding", "Số kết quả bị ảnh hưởng");
        for (SeverityCountView severity : severities) {
            row(html, severity.severity(), severity.findings(), severity.checksAffected());
        }
        html.append("</table>\n");
    }

    private static void rules(StringBuilder html, String title, RuleRankingView ranking) {
        html.append("<h3>").append(esc(title)).append("</h3>\n");
        if (ranking.items().isEmpty()) {
            html.append("<p>Không có finding nào.</p>\n");
            return;
        }
        html.append("<table>\n");
        header(html, "Rule", "Mức", "Số finding", "Số kết quả bị ảnh hưởng");
        for (HealthReport.RuleCountView item : ranking.items()) {
            row(html, item.ruleId(), item.severity(), item.findings(), item.checksAffected());
        }
        html.append("</table>\n<p class=\"note\">").append(ranking.items().size()).append(" trên ").append(ranking.distinctRules())
                .append(" rule (và mức) khác nhau, xếp theo số finding rồi số kết quả bị ảnh hưởng.</p>\n");
    }

    private static void marks(StringBuilder html, MarkRankingView ranking) {
        html.append("<h3>trademark-risk: mark bị khớp nhiều nhất</h3>\n");
        if (ranking.items().isEmpty()) {
            html.append("<p>Không có mark nào bị khớp.</p>\n");
            return;
        }
        html.append("<table>\n");
        header(html, "Mark (của catalog)", "Số finding", "Số kết quả bị ảnh hưởng", "Mức cao nhất");
        for (HealthReport.MarkCountView item : ranking.items()) {
            row(html, item.mark(), item.findings(), item.checksAffected(), item.highestRisk());
        }
        html.append("</table>\n<p class=\"note\">").append(ranking.items().size()).append(" trên ").append(ranking.distinctMarks())
                .append(" mark khác nhau. Mark là chữ của catalog, không phải chữ trong listing.</p>\n");
    }

    private static String pairs(Map<String, Long> counts) {
        return counts.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(" · "));
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * ratio);
    }

    private static void header(StringBuilder html, String... names) {
        html.append("<tr>");
        for (String name : names) {
            html.append("<th>").append(esc(name)).append("</th>");
        }
        html.append("</tr>\n");
    }

    /** A row of cells; every cell is escaped, and a number is right-aligned. */
    private static void row(StringBuilder html, Object... cells) {
        html.append("<tr>");
        for (Object cell : cells) {
            html.append(cell instanceof Number ? "<td class=\"n\">" : "<td>").append(esc(String.valueOf(cell))).append("</td>");
        }
        html.append("</tr>\n");
    }

    /** HTML-escapes text that did not come from this class. */
    static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
