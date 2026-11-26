package com.thanhdatpb.listingguard.api.console.phrase;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.bulk.BulkExport;
import java.util.Arrays;
import java.util.stream.Collectors;

final class PhraseConsoleExports {
    private PhraseConsoleExports() {}

    static String safeText(PhraseConsoleResults.Result result) {
        return result.rows().stream().filter(PhraseConsoleResults.Row::exportable)
                .map(row -> BulkExport.defuse(BulkExport.oneLine(row.view().text()))).collect(Collectors.joining("\n"));
    }

    static String csv(ConsoleMessages messages, PhraseConsoleResults.Result result, boolean safe) {
        StringBuilder out = new StringBuilder("\ufeff");
        if (safe) {
            line(out, messages.get("phrase.col.text"), messages.get("phrase.col.volume"));
        } else {
            line(out, messages.get("phrase.col.text"), messages.get("phrase.col.volume"), messages.get("phrase.col.verdict"),
                    messages.get("phrase.col.mark"), messages.get("phrase.col.rule"), messages.get("phrase.col.risk"),
                    messages.get("phrase.col.source"), messages.get("phrase.col.serial"), messages.get("phrase.col.link"));
        }
        for (var row : result.rows()) {
            var view = row.view();
            String volume = view.searchVolume() == null ? "" : view.searchVolume().toString();
            if (safe) {
                if (row.exportable()) {
                    line(out, view.text(), volume);
                }
            } else if (row.findings().isEmpty()) {
                line(out, view.text(), volume, view.verdict().name(), "", "", "", "", "", "");
            } else {
                for (var entry : row.findings()) {
                    var finding = entry.view();
                    String serials = finding.sourceIds().stream().filter(id -> id.matches("uspto:[0-9]{8}"))
                            .map(id -> id.substring("uspto:".length())).collect(Collectors.joining(" "));
                    line(out, view.text(), volume, view.verdict().name(), finding.mark(), finding.ruleId(),
                            finding.risk().name(), finding.source().name(), serials,
                            finding.sourceLinks().stream().map(link -> link.url()).collect(Collectors.joining(" ")));
                }
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String... values) {
        out.append(Arrays.stream(values).map(value -> BulkExport.cell(value == null ? "" : value.stripLeading()))
                .collect(Collectors.joining(","))).append("\r\n");
    }
}
