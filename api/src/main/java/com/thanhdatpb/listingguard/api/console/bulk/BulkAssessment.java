package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Detail;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Item;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Kind;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * Reduces the three checks of one row to what the table shows (ADR-029): a status, a count, the main finding and the line that is
 * pasted back into the Sheet. It decides nothing about a listing that a rule did not decide: it only orders and counts. Its sentences
 * are messages (ADR-028), in the language of the page.
 *
 * <p>A row is {@code ERROR} when a listing rule (or a variation rule) found an ERROR; {@code WARNING} for a WARNING, a trademark risk of
 * MEDIUM or HIGH, or any part that is INCOMPLETE (a result that could not be completed is never read as clean); otherwise
 * {@code OK}, which means no listing rule found an error or warning and says nothing about the marketplace or about a trademark
 * that was not checked (the summary says so).
 */
final class BulkAssessment {

    /** The most characters of a summary cell; the pasted text stays readable in a Sheet cell. */
    static final int SUMMARY_MAX_CHARS = 240;

    record Assessed(Status status, int findings, Item main, String summary) {
    }

    private BulkAssessment() {
    }

    static Assessed of(ConsoleMessages messages, Detail detail) {
        if (detail.problem() != null) {
            return new Assessed(Status.UNREADABLE, 0, null, summary(messages.get("bulk.summary.unreadable", detail.problem())));
        }
        BulkResult.Rules rules = detail.rules();
        if (!rules.ran()) {
            return new Assessed(Status.NOT_CHECKED, 0, null, summary(messages.get("bulk.summary.notchecked", rules.note())));
        }
        BulkResult.Trademark trademark = detail.trademark();
        BulkResult.Variation variation = detail.variation();
        int findings = rules.items().size() + trademark.items().size() + variation.items().size();

        boolean error = hasLevel(rules.items(), "error") || hasLevel(variation.items(), "error");
        boolean warning = hasLevel(rules.items(), "warning") || hasLevel(variation.items(), "warning")
                || hasLevel(trademark.items(), "high") || hasLevel(trademark.items(), "medium")
                || trademark.kind() == Kind.INCOMPLETE || variation.kind() == Kind.INCOMPLETE
                || "INCOMPLETE".equals(rules.readiness());
        Status status = error ? Status.ERROR : warning ? Status.WARNING : Status.OK;

        Item main = null;
        int best = Integer.MAX_VALUE;
        for (Item item : rules.items()) {
            int rank = rank("rules", item.level());
            if (rank < best) {
                best = rank;
                main = item;
            }
        }
        for (Item item : trademark.items()) {
            int rank = rank("trademark", item.level());
            if (rank < best) {
                best = rank;
                main = item;
            }
        }
        for (Item item : variation.items()) {
            int rank = rank("variation", item.level());
            if (rank < best) {
                best = rank;
                main = item;
            }
        }

        StringBuilder text = new StringBuilder();
        if (findings == 0) {
            text.append(messages.get("bulk.summary.none"));
        } else {
            text.append(messages.get("bulk.summary.some", findings, main.message()));
        }
        List<String> notes = new ArrayList<>();
        if (trademark.kind() == Kind.NOT_RUN) {
            notes.add(messages.get("bulk.summary.trademark.notrun", trademark.note()));
        } else if (trademark.kind() == Kind.INCOMPLETE) {
            notes.add(messages.get("bulk.summary.trademark.incomplete"));
        }
        if ("INCOMPLETE".equals(rules.readiness())) {
            notes.add(messages.get("bulk.summary.rules.incomplete"));
        }
        if (variation.kind() == Kind.NOT_RUN) {
            notes.add(messages.get("bulk.summary.variation.notrun", variation.note()));
        } else if (variation.kind() == Kind.INCOMPLETE) {
            notes.add(messages.get("bulk.summary.variation.incomplete"));
        }
        for (String note : notes) {
            text.append(" [").append(note).append(']');
        }
        return new Assessed(status, findings, main, summary(text.toString()));
    }

    /** Lower is more important: rule ERROR, trademark HIGH, rule WARNING, variation WARNING, trademark MEDIUM, information, trademark LOW. */
    static int rank(String check, String level) {
        return switch (check + ":" + level) {
            case "rules:error", "variation:error" -> 0;
            case "trademark:high" -> 1;
            case "rules:warning" -> 2;
            case "variation:warning" -> 3;
            case "trademark:medium" -> 4;
            case "rules:info", "variation:info" -> 5;
            default -> 6;
        };
    }

    private static boolean hasLevel(List<Item> items, String level) {
        return items.stream().anyMatch(item -> level.equals(item.level()));
    }

    /** One line (no tab, no line break), cut to {@link #SUMMARY_MAX_CHARS} characters at a code point. */
    static String summary(String text) {
        String line = BulkExport.oneLine(text);
        if (line.codePointCount(0, line.length()) <= SUMMARY_MAX_CHARS) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, SUMMARY_MAX_CHARS - 1)) + "…";
    }
}
