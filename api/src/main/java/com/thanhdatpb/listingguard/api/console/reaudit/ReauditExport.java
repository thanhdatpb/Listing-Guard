package com.thanhdatpb.listingguard.api.console.reaudit;

import static com.thanhdatpb.listingguard.api.console.bulk.BulkExport.cell;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkExport.csvHref;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditResult.Risk;
import java.util.List;
import java.util.StringJoiner;

/**
 * The list of what needs fixing, as a CSV (ADR-035): one line per risk with the SKU, the account label, the rule, the mark and the
 * level, then the group (new or known), the line of the file and the field. It never holds the text of a finding (that can quote the
 * listing). UTF-8 with a byte order mark and {@code \r\n}; a cell a spreadsheet would run as a formula gets a leading apostrophe, like
 * the bulk check's file.
 */
final class ReauditExport {

    private ReauditExport() {
    }

    static String csv(ConsoleMessages messages, String account, List<Risk> risks) {
        StringBuilder out = new StringBuilder("﻿");
        StringJoiner head = new StringJoiner(",");
        for (String key : List.of("reaudit.export.sku", "reaudit.export.account", "reaudit.export.rule", "reaudit.export.mark",
                "reaudit.export.level", "reaudit.export.group", "reaudit.export.line", "reaudit.export.field")) {
            head.add(cell(messages.get(key)));
        }
        out.append(head).append("\r\n");
        for (Risk risk : risks) {
            StringJoiner line = new StringJoiner(",");
            line.add(cell(risk.sku()));
            line.add(cell(account));
            line.add(cell(risk.ruleId()));
            line.add(cell(risk.mark()));
            line.add(cell(risk.levelLabel()));
            line.add(cell(messages.get(risk.isNew() ? "reaudit.group.new" : "reaudit.group.known")));
            line.add(String.valueOf(risk.line()));
            line.add(cell(risk.field()));
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    static String href(String csv) {
        return csvHref(csv);
    }
}
