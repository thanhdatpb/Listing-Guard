package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.feedback.FeedbackStats;
import java.util.Locale;

/**
 * The CSV of the console statistics page (ADR-034), for a manager's report: UTF-8 with a byte order mark (a spreadsheet then reads
 * the Vietnamese headers right), one section per table, the headers in the language of the page. A cell that starts with {@code =},
 * {@code +}, {@code -}, {@code @}, a tab or a carriage return gets a leading apostrophe, so a spreadsheet never runs it as a formula
 * (like the bulk check's CSV). No note and no text of a listing is in it.
 */
final class StatsCsv {

    private StatsCsv() {
    }

    static String csv(ConsoleMessages messages, FeedbackStats stats) {
        StringBuilder out = new StringBuilder("﻿");
        line(out, messages.get("stats.csv.period"), stats.period().from().toString(), stats.period().to().toString(),
                stats.marketplace() == null ? messages.get("report.form.all") : stats.marketplace());
        line(out, messages.get("stats.col.verdicts"), String.valueOf(stats.totals().verdicts()),
                messages.get("stats.col.rate"), rate(stats.totals().falsePositiveRate()));
        out.append("\r\n");
        line(out, messages.get("stats.byrule.heading"));
        line(out, messages.get("report.col.pack"), messages.get("report.col.rule"), messages.get("stats.col.source"),
                messages.get("report.col.findings"), messages.get("stats.col.verdicts"), messages.get("feedback.verdict.correct"),
                messages.get("feedback.verdict.false-positive"), messages.get("feedback.verdict.not-useful"),
                messages.get("stats.col.rate"));
        for (FeedbackStats.RuleFeedbackView rule : stats.byRule()) {
            line(out, rule.pack(), rule.ruleId(), rule.source(), String.valueOf(rule.findings()), String.valueOf(rule.verdicts()),
                    String.valueOf(rule.correct()), String.valueOf(rule.falsePositive()), String.valueOf(rule.notUseful()),
                    rate(rule.falsePositiveRate()));
        }
        out.append("\r\n");
        line(out, messages.get("stats.byreviewer.heading"));
        line(out, messages.get("stats.col.reviewer"), messages.get("stats.col.kind"), messages.get("stats.col.verdicts"),
                messages.get("feedback.verdict.correct"), messages.get("feedback.verdict.false-positive"),
                messages.get("feedback.verdict.not-useful"));
        for (FeedbackStats.ReviewerFeedbackView reviewer : stats.byReviewer()) {
            line(out, reviewer.display(), reviewer.kind(), String.valueOf(reviewer.verdicts()), String.valueOf(reviewer.correct()),
                    String.valueOf(reviewer.falsePositive()), String.valueOf(reviewer.notUseful()));
        }
        return out.toString();
    }

    /** A rate as a decimal with four places (a spreadsheet formats it), or empty when there is no verdict. */
    static String rate(Double rate) {
        return rate == null ? "" : String.format(Locale.ROOT, "%.4f", rate);
    }

    static void line(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
