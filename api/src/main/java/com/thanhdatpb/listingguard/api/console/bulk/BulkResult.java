package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import java.util.List;

/**
 * What the result page shows after a bulk run (ADR-029): one {@link Row} per input row, in the input's order, the counts of each
 * status, the notes that hold for the whole run and the two texts the seller takes away (the block to paste into a Sheet and the CSV
 * link). It is built for the response and never kept; every text in it is already in the language of the page (ADR-028).
 *
 * @param header     whether the input had a header row, which decides whether the pasted block starts with a header line
 * @param notes      what holds for the whole run (a check that did not run, a quota that ran out)
 * @param disclaimer the disclaimer of the active trademark ruleset, printed unchanged next to the table
 * @param sheetBlock two columns, status and summary, one line per input row, to paste into a Sheet
 * @param csvHref    a {@code data:} link to the same rows as a CSV file
 */
public record BulkResult(String marketplace, boolean header, List<Row> rows, Counts counts, List<String> notes, String disclaimer,
                         String sheetBlock, String csvHref) {

    /**
     * The statuses of a row. The first three judge the listing; the others say why it was not judged. {@code key} is the message of
     * the label, {@code css} the class of the badge and {@code group} what the filter buttons use.
     */
    public enum Status {
        OK("bulk.status.ok", "ok", "OK"),
        WARNING("bulk.status.warning", "warning", "WARNING"),
        ERROR("bulk.status.error", "error", "ERROR"),
        UNREADABLE("bulk.status.unreadable", "unreadable", "OTHER"),
        NOT_CHECKED("bulk.status.notchecked", "not-checked", "OTHER"),
        EMPTY("bulk.status.empty", "empty", "OTHER");

        private final String key;
        private final String css;
        private final String group;

        Status(String key, String css, String group) {
            this.key = key;
            this.css = css;
            this.group = group;
        }

        /** The label of this status in the language of the page. */
        public String label(ConsoleMessages messages) {
            return messages.get(key);
        }

        public String css() {
            return css;
        }

        /** What the filter buttons use: ERROR, WARNING, OK, or OTHER for every row that was not judged. */
        public String group() {
            return group;
        }
    }

    /**
     * @param index       the position among the data rows, from 1
     * @param line        the line of the text where the row starts (the header, when there is one, is line 1)
     * @param sku         the SKU the table gave the row, or null
     * @param statusLabel the label of the status, in the language of the page
     * @param findings    how many findings the row has, all checks together
     * @param main        the finding that decides the status, or null
     * @param summary     one line for the Sheet: the count and the main finding, and what did not run
     */
    public record Row(int index, int line, String sku, Status status, String statusLabel, int findings, Item main, String summary,
                      Detail detail) {
    }

    /** The three checks of a row, and why a row could not be read. */
    public record Detail(Rules rules, Trademark trademark, Variation variation, String problem) {

        static Detail unread(String problem) {
            return new Detail(Rules.notRun(null), Trademark.notRun(null), Variation.none(), problem);
        }

        static Detail empty() {
            return new Detail(Rules.notRun(null), Trademark.notRun(null), Variation.none(), null);
        }
    }

    /**
     * @param ran            false when the listing rules did not run for this row ({@code note} says why)
     * @param readiness      READY, NOT_READY or INCOMPLETE, or null when the rules did not run
     * @param readinessLabel the label of the readiness, in the language of the page; null when the rules did not run
     * @param score          the readiness score; null when there is none (never zero)
     */
    public record Rules(boolean ran, String note, String readiness, String readinessLabel, Integer score, List<Item> items) {

        static Rules notRun(String note) {
            return new Rules(false, note, null, null, null, List.of());
        }
    }

    public enum Kind {
        /** the check ran and every rule did */
        RESULT,
        /** a rule could not run: what was found is kept, and the result is never read as clean */
        INCOMPLETE,
        /** the check did not run for this row ({@code note} says why) */
        NOT_RUN,
        /** variation only: the row belongs to no family */
        NONE
    }

    /** @param level the highest level found (LOW, MEDIUM or HIGH); null when none was established (never LOW for INCOMPLETE) */
    public record Trademark(Kind kind, String level, String note, List<Item> items) {

        static Trademark notRun(String note) {
            return new Trademark(Kind.NOT_RUN, null, note, List.of());
        }
    }

    public record Variation(Kind kind, String note, List<Item> items) {

        static Variation none() {
            return new Variation(Kind.NONE, null, List.of());
        }

        static Variation notRun(String note) {
            return new Variation(Kind.NOT_RUN, note, List.of());
        }
    }

    /**
     * One finding. {@code level} is the CSS and sort key (error, warning, info; high, medium, low) and {@code levelLabel} what the
     * page prints. {@code detail} carries what the finding is about beyond its message (the catalog mark of a trademark finding).
     * {@code explanation} says what the rule checks in general, in the language of the page (null when there is none). {@code fix}
     * and {@code fixValue} are a verified suggestion of the rules, shown and never applied: {@code fixValue} is null for a fix that
     * removes the value.
     */
    public record Item(String level, String levelLabel, String ruleId, String field, String message, String suggestion, String detail,
                       String explanation, String fix, String fixValue) {
    }

    /** How many rows have each status. */
    public record Counts(int total, int ok, int warning, int error, int unreadable, int notChecked, int empty) {

        static Counts of(List<Row> rows) {
            return new Counts(rows.size(), count(rows, Status.OK), count(rows, Status.WARNING), count(rows, Status.ERROR),
                    count(rows, Status.UNREADABLE), count(rows, Status.NOT_CHECKED), count(rows, Status.EMPTY));
        }

        private static int count(List<Row> rows, Status status) {
            return (int) rows.stream().filter(row -> row.status() == status).count();
        }

        /** The rows that were judged. */
        public int judged() {
            return ok + warning + error;
        }

        public int others() {
            return unreadable + notChecked + empty;
        }
    }

    static BulkResult of(ConsoleMessages messages, String marketplace, boolean header, List<Row> rows, List<String> notes,
                         String disclaimer) {
        String csv = BulkExport.csv(messages, rows);
        return new BulkResult(marketplace, header, List.copyOf(rows), Counts.of(rows), List.copyOf(notes), disclaimer,
                BulkExport.sheetBlock(messages, rows, header), BulkExport.csvHref(csv));
    }
}
