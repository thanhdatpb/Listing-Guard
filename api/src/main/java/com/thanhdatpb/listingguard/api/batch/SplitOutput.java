package com.thanhdatpb.listingguard.api.batch;

import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.thanhdatpb.listingguard.api.batch.ListingOutcome.Part;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The review gate of a batch run ({@code --split-output}): the rows of the input file sorted into {@code passed.csv} and
 * {@code held.csv}, so that a person reviews only the held ones. Nothing here edits a listing, applies a fix or says that a
 * listing is allowed: a row is only copied, with the cells it had.
 *
 * <p>Both files keep the columns and the order of the input file (the header as the batch read it, trimmed, the same
 * delimiter and the same text encoding, no byte order mark, no change to any cell). {@code held.csv} has one more column,
 * {@code hold_reasons}, with {@code ruleId/SEVERITY} items separated by {@code "; "} (ASCII only, so it fits every
 * encoding): an {@code ERROR} finding of the rule check is {@code <ruleId>/ERROR}, a trademark level at or above the hold
 * level of the person who ran the batch is {@code trademark/<LEVEL>}. A row that could not be checked is held, never
 * passed, whatever else is known about it: {@code unreadable_row}, {@code rules/CHECK_FAILED}, {@code rules/QUOTA_EXCEEDED},
 * {@code trademark/CHECK_FAILED}, {@code trademark/QUOTA_EXCEEDED}, and {@code trademark/UNKNOWN} for an incomplete
 * trademark result (UNKNOWN is never read as LOW). A trademark catalog that is not served ({@code NOT_CONFIGURED}) holds
 * nothing: the job says so on the console.
 *
 * <p>The hold level is the choice of the person who ran the batch ({@link TrademarkHold}), not a legal conclusion and not a
 * marketplace rule. The cells are the listing text as it came: nothing is prefixed against spreadsheet formulas here,
 * because the files are meant to be fed back to a tool, so open them as you would open the input.
 */
final class SplitOutput implements AutoCloseable {

    static final String PASSED_FILE = "passed.csv";
    static final String HELD_FILE = "held.csv";
    static final String HOLD_REASONS_COLUMN = "hold_reasons";

    private static final CsvMapper MAPPER = new CsvMapper();

    /** Which trademark level, and above, holds a listing back. */
    enum TrademarkHold {
        /** hold at HIGH (the default) */
        HIGH,
        /** hold at MEDIUM and HIGH */
        MEDIUM,
        /** the trademark check never holds a listing back */
        NONE;

        static TrademarkHold parse(String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BatchConfigException("--hold-trademark-risk must be HIGH, MEDIUM or NONE; got '" + text + "'.");
            }
        }

        /** Whether a determined level is at or above this hold level. */
        boolean holds(RiskLevel level) {
            return switch (this) {
                case NONE -> false;
                case MEDIUM -> level == RiskLevel.MEDIUM || level == RiskLevel.HIGH;
                case HIGH -> level == RiskLevel.HIGH;
            };
        }
    }

    /** How many rows went where. */
    record Counts(int passed, int held) {
    }

    private final List<String> header;
    private final TrademarkHold hold;
    private final BufferedWriter passedFile;
    private final BufferedWriter heldFile;
    private final SequenceWriter passed;
    private final SequenceWriter held;
    private int passedRows;
    private int heldRows;

    /**
     * Creates {@code passed.csv} and {@code held.csv} in {@code dir} (an earlier file of the same name is replaced) and writes
     * their header.
     *
     * @param header the header of the input file, as {@link BatchCsvReader#header()} gives it
     */
    SplitOutput(Path dir, BatchMapping.Csv csv, List<String> header, TrademarkHold hold) {
        this.header = header;
        this.hold = hold;
        BufferedWriter passedWriter = null;
        BufferedWriter heldWriter = null;
        try {
            Files.createDirectories(dir);
            CsvSchema schema = CsvSchema.emptySchema().withColumnSeparator(csv.delimiter());
            passedWriter = Files.newBufferedWriter(dir.resolve(PASSED_FILE), csv.encoding());
            heldWriter = Files.newBufferedWriter(dir.resolve(HELD_FILE), csv.encoding());
            this.passedFile = passedWriter;
            this.heldFile = heldWriter;
            this.passed = MAPPER.writer(schema).writeValues(passedWriter);
            this.held = MAPPER.writer(schema).writeValues(heldWriter);
            this.passed.write(header.toArray(new String[0]));
            List<String> heldHeader = new ArrayList<>(header);
            heldHeader.add(HOLD_REASONS_COLUMN);
            this.held.write(heldHeader.toArray(new String[0]));
        } catch (IOException e) {
            closeQuietly(passedWriter);
            closeQuietly(heldWriter);
            throw new BatchConfigException("Cannot write " + PASSED_FILE + " and " + HELD_FILE + " to " + dir + ": "
                    + e.getMessage(), e);
        }
    }

    /** Copies the row to {@code passed.csv} or, with its reasons, to {@code held.csv}. */
    void add(BatchCsvReader.Row row, ListingOutcome outcome) {
        List<String> reasons = holdReasons(outcome, hold);
        try {
            if (reasons.isEmpty()) {
                passed.write(cells(row, null));
                passedRows++;
            } else {
                held.write(cells(row, String.join("; ", reasons)));
                heldRows++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Counts counts() {
        return new Counts(passedRows, heldRows);
    }

    /** The cells as read, padded to the header width, and the reasons after the last one when there are some. */
    private String[] cells(BatchCsvReader.Row row, String reasons) {
        int width = Math.max(header.size(), row.cells().length);
        String[] out = new String[reasons == null ? width : width + 1];
        for (int i = 0; i < width; i++) {
            String cell = i < row.cells().length ? row.cells()[i] : null;
            out[i] = cell == null ? "" : cell;
        }
        if (reasons != null) {
            out[width] = reasons;
        }
        return out;
    }

    /**
     * Why a listing is held, in the order they are found; empty when it passes. Pure, so a test can hold it against the
     * results it builds.
     */
    static List<String> holdReasons(ListingOutcome outcome, TrademarkHold hold) {
        if (outcome.isUnreadable()) {
            return List.of("unreadable_row");
        }
        Set<String> reasons = new LinkedHashSet<>();
        Part<CheckResult> rules = outcome.rules();
        switch (rules.state()) {
            case DONE -> {
                for (CheckResult.FindingView finding : rules.result().findings()) {
                    if (finding.severity() == Severity.ERROR) {
                        reasons.add(finding.ruleId() + "/ERROR");
                    }
                }
            }
            case FAILED -> reasons.add("rules/CHECK_FAILED");
            case QUOTA_EXCEEDED -> reasons.add("rules/QUOTA_EXCEEDED");
            case NOT_CONFIGURED -> {
                // the rule check is always configured; nothing to hold on
            }
        }
        if (hold != TrademarkHold.NONE) {
            Part<TrademarkCheckResult> trademark = outcome.trademark();
            switch (trademark.state()) {
                case DONE -> {
                    TrademarkCheckResult result = trademark.result();
                    if (result.completeness() != Completeness.COMPLETE || !result.riskLevel().isDetermined()) {
                        reasons.add("trademark/UNKNOWN");
                    } else if (hold.holds(result.riskLevel())) {
                        reasons.add("trademark/" + result.riskLevel());
                    }
                }
                case FAILED -> reasons.add("trademark/CHECK_FAILED");
                case QUOTA_EXCEEDED -> reasons.add("trademark/QUOTA_EXCEEDED");
                case NOT_CONFIGURED -> {
                    // not served on this deployment: the job tells the operator that passed.csv says nothing about trademarks
                }
            }
        }
        return List.copyOf(reasons);
    }

    @Override
    public void close() {
        IOException failure = null;
        for (SequenceWriter writer : Arrays.asList(passed, held)) {
            try {
                writer.close();
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        closeQuietly(passedFile);
        closeQuietly(heldFile);
        if (failure != null) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                // nothing more to do
            }
        }
    }
}
