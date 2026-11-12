package com.thanhdatpb.listingguard.api.policymonitor;

import static com.thanhdatpb.listingguard.api.policymonitor.PolicyDiffEngine.*;

/** Small standalone HTML rendering of the deterministic JSON report. */
public final class PolicyMonitorHtml {
    private PolicyMonitorHtml() {}

    public static String render(RunFile report) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Policy change report</title>")
                .append("<style>body{font:15px system-ui;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#17212b}h1,h2{color:#12334a}table{border-collapse:collapse;width:100%}td,th{padding:.55rem;border-bottom:1px solid #d8e0e6;text-align:left;vertical-align:top}.hash{font:12px monospace;overflow-wrap:anywhere}.tag{font-weight:700;color:#8a4b08}</style>")
                .append("<body><h1>Policy change report</h1><p>Generated ").append(esc(report.generatedAt().toString()))
                .append(report.dryRun() ? " · dry run" : "").append("</p>");
        if (report.empty()) html.append("<p>No policy changes or alerts.</p>");
        html.append("<h2>Documents and sections</h2>");
        if (report.changes().isEmpty()) html.append("<p>None</p>");
        for (DocumentChange doc : report.changes()) {
            html.append("<h3>").append(esc(doc.type())).append(" — ").append(esc(doc.title())).append("</h3><p><a href=\"")
                    .append(esc(doc.url())).append("\">").append(esc(doc.url())).append("</a></p><p>Version: ")
                    .append(esc(doc.oldVersion())).append(" → ").append(esc(doc.newVersion())).append("</p>");
            if (doc.documentHashChanged()) html.append("<p>Document hash changed: <span class=\"hash\">")
                    .append(esc(doc.oldHash())).append(" → ").append(esc(doc.newHash())).append("</span></p>");
            if (!doc.sections().isEmpty()) {
                html.append("<table><thead><tr><th>Change</th><th>Section path</th><th>Hash</th><th>Excerpt (max 200 chars)</th></tr></thead><tbody>");
                for (SectionChange section : doc.sections()) html.append("<tr><td>").append(esc(section.type())).append("</td><td>")
                        .append(esc(section.path())).append("</td><td class=\"hash\">").append(esc(section.oldHash())).append(" → ")
                        .append(esc(section.newHash())).append("</td><td>").append(esc(section.excerpt())).append("</td></tr>");
                html.append("</tbody></table>");
            }
        }
        html.append("<h2>Alerts</h2>");
        if (report.alerts().isEmpty()) html.append("<p>None</p>");
        for (Alert alert : report.alerts()) html.append("<p class=\"tag\">").append(esc(alert.code())).append(" · ")
                .append(esc(alert.url())).append(" — ").append(esc(alert.message())).append("</p>");
        html.append("<h2>Rules to review</h2>");
        if (report.rulesToReview().isEmpty()) html.append("<p>None</p>");
        else {
            html.append("<table><thead><tr><th>Marketplace</th><th>Ruleset</th><th>Rule</th><th>Source status</th><th>URL / section</th></tr></thead><tbody>");
            for (RuleReview rule : report.rulesToReview()) html.append("<tr><td>").append(esc(rule.marketplace())).append("</td><td>")
                    .append(esc(rule.rulesetVersion())).append("</td><td>").append(esc(rule.ruleId())).append("</td><td>")
                    .append(esc(rule.sourceStatus())).append("</td><td>").append(esc(rule.sourceUrl())).append("<br>")
                    .append(esc(rule.section())).append("</td></tr>");
            html.append("</tbody></table>");
        }
        return html.append("</body></html>").toString();
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
