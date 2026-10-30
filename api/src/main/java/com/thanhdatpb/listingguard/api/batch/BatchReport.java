package com.thanhdatpb.listingguard.api.batch;

import com.thanhdatpb.listingguard.api.batch.ListingOutcome.Part;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Writes the reports of a batch while it runs, so memory stays flat however many listings there are:
 * {@code findings.csv} (one line per finding), {@code listings.csv} (one line per listing) and, last, {@code summary.html}
 * (one self-contained page for a manager). Rows are written in the order of the input file and findings in the order the
 * checks return them, so the same input against the same results gives the same files; the only thing in them that
 * changes between runs is the run time shown in {@code summary.html}. Nothing here is a secret: the reports hold listing
 * text (snippets, values) and results, never a key or a connection setting.
 *
 * <p>The CSV files are UTF-8 with a byte order mark, so a spreadsheet reads the Vietnamese messages correctly. A text
 * cell that starts with {@code =}, {@code +}, {@code -} or {@code @} gets a leading apostrophe: listing text comes from
 * outside, and a spreadsheet would run it as a formula.
 *
 * <p>{@code findings.csv}: {@code id, line, check (rules|trademark), ruleId, severityRisk (ERROR/WARNING/INFO for rules,
 * LOW/MEDIUM/HIGH for trademark), field, snippet, mark, sourceIds, message, suggestion}.<br>
 * {@code listings.csv}: {@code id, line, status (OK|INPUT_ERROR|CHECK_ERROR), label, trademarkRisk (the highest level, or
 * NOT_CONFIGURED|ERROR), completeness, ruleErrors, ruleWarnings, rulesCheckId, trademarkCheckId, error}.
 */
final class BatchReport implements AutoCloseable {

    static final String FINDINGS_FILE = "findings.csv";
    static final String LISTINGS_FILE = "listings.csv";
    static final String SUMMARY_FILE = "summary.html";

    /** A snippet or value longer than this many characters is cut, so a long description cannot fill a cell. */
    static final int SNIPPET_MAX_CHARS = 120;
    /** How many rows with problems the summary lists; the rest are in {@code listings.csv}. */
    static final int ERROR_ROWS_SHOWN = 50;
    /** How many marks and rules the summary ranks. */
    static final int TOP = 10;

    private static final String FINDINGS_HEADER = "id,line,check,ruleId,severityRisk,field,snippet,mark,sourceIds,message,suggestion";
    private static final String LISTINGS_HEADER = "id,line,status,label,trademarkRisk,completeness,ruleErrors,ruleWarnings,"
            + "rulesCheckId,trademarkCheckId,error";

    /** Run-wide facts the summary shows next to the counts. */
    record RunInfo(Instant runAt, String client, String marketplace, String inputName, String mappingName,
                   String labelColumn, Integer maxRows, boolean stoppedAtMaxRows, String abortReason,
                   boolean trademarkReady, String trademarkDetail, String disclaimerFallback) {
    }

    /** A mark or a rule with how many listings and findings it touches. */
    record Ranked(String name, int listings, int findings, String highest) {
    }

    /** A row that could not be checked or whose check failed. */
    record ErrorRow(int line, String id, String message) {
    }

    /** Everything the summary page shows. */
    record Totals(int rows, int unreadable, int failed, Map<String, Integer> trademarkRisk, int complete, int incomplete,
                  int listingsWithRuleErrors, int listingsWithRuleWarnings, int ruleErrors, int ruleWarnings,
                  int ruleInfos, int distinctRules, int distinctMarks, List<Ranked> topRules, List<Ranked> topMarks,
                  Set<String> rulesetVersions, Set<String> ruleAnalysisVersions, Set<String> trademarkRulesetVersions,
                  Set<String> catalogVersions, Set<String> trademarkAnalysisVersions, String disclaimer,
                  Measurement measurement, List<ErrorRow> errorRows, int errorRowsTotal) {
    }

    private static final class Stat {
        int listings;
        int findings;
        int rank = -1;
        String highest = "";

        void add(int rankOfFinding, String name) {
            findings++;
            if (rankOfFinding > rank) {
                rank = rankOfFinding;
                highest = name;
            }
        }
    }

    private final Path dir;
    private final BufferedWriter findings;
    private final BufferedWriter listings;
    private boolean writersClosed;

    private int rows;
    private int unreadable;
    private int failed;
    private final Map<String, Integer> trademarkRisk = new TreeMap<>();
    private int complete;
    private int incomplete;
    private int listingsWithRuleErrors;
    private int listingsWithRuleWarnings;
    private int ruleErrors;
    private int ruleWarnings;
    private int ruleInfos;
    private final Map<String, Stat> ruleStats = new TreeMap<>();
    private final Map<String, Stat> markStats = new TreeMap<>();
    private final Set<String> rulesetVersions = new TreeSet<>();
    private final Set<String> ruleAnalysisVersions = new TreeSet<>();
    private final Set<String> trademarkRulesetVersions = new TreeSet<>();
    private final Set<String> catalogVersions = new TreeSet<>();
    private final Set<String> trademarkAnalysisVersions = new TreeSet<>();
    private String disclaimer;
    private final Measurement measurement = new Measurement();
    private final List<ErrorRow> errorRows = new ArrayList<>();
    private int errorRowsTotal;

    /**
     * Prepares the directory and the two CSV files. Reports of an earlier run in the directory are removed first, so a
     * directory never mixes two runs: {@code summary.html} is written last, and a directory without it did not finish.
     */
    BatchReport(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            for (String name : List.of(FINDINGS_FILE, LISTINGS_FILE, SUMMARY_FILE)) {
                Files.deleteIfExists(dir.resolve(name));
            }
            this.findings = open(FINDINGS_FILE, FINDINGS_HEADER);
            this.listings = open(LISTINGS_FILE, LISTINGS_HEADER);
        } catch (IOException e) {
            throw new BatchConfigException("Cannot write the reports to " + dir + ": " + e.getMessage(), e);
        }
    }

    private BufferedWriter open(String name, String header) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
        writer.write('﻿');
        writer.write(header);
        writer.write('\n');
        return writer;
    }

    void add(ListingOutcome outcome) {
        rows++;
        if (outcome.isUnreadable()) {
            unreadable++;
            trademarkRisk.merge(Risk.NOT_CHECKED, 1, Integer::sum);
            measurement.addNotChecked();
            noteProblem(outcome.line(), outcome.id(), outcome.inputError());
            writeListing(outcome, "INPUT_ERROR", "", "", "", "", "", "", outcome.inputError());
            return;
        }
        Part<CheckResult> rules = outcome.rules();
        Part<TrademarkCheckResult> trademark = outcome.trademark();
        boolean checkFailed = rules.state() == Part.State.FAILED || trademark.state() == Part.State.FAILED;
        String problem = null;
        if (checkFailed) {
            failed++;
            problem = (rules.state() == Part.State.FAILED ? "rules: " + rules.message() : "")
                    + (rules.state() == Part.State.FAILED && trademark.state() == Part.State.FAILED ? "; " : "")
                    + (trademark.state() == Part.State.FAILED ? "trademark: " + trademark.message() : "");
            noteProblem(outcome.line(), outcome.id(), problem);
        }
        measurement.add(outcome.label(), verdict(trademark));

        String ruleErrorsCell = "";
        String ruleWarningsCell = "";
        String rulesCheckId = "";
        if (rules.isDone()) {
            CheckResult result = rules.result();
            rulesCheckId = result.checkId().toString();
            ruleErrorsCell = Integer.toString(result.summary().errors());
            ruleWarningsCell = Integer.toString(result.summary().warnings());
            countRules(outcome, result);
        }
        String risk = riskCell(trademark);
        String completeness = "";
        String trademarkCheckId = "";
        if (trademark.isDone()) {
            TrademarkCheckResult result = trademark.result();
            trademarkCheckId = result.checkId().toString();
            completeness = result.completeness().name();
            countTrademark(outcome, result);
        } else {
            trademarkRisk.merge(trademark.state() == Part.State.NOT_CONFIGURED ? Risk.NOT_CONFIGURED : Risk.FAILED, 1,
                    Integer::sum);
        }
        writeListing(outcome, checkFailed ? "CHECK_ERROR" : "OK", risk, completeness, ruleErrorsCell, ruleWarningsCell,
                rulesCheckId, trademarkCheckId, problem == null ? "" : problem);
    }

    /** Names used for the trademark distribution besides the risk levels. */
    static final class Risk {
        static final String NOT_CONFIGURED = "NOT_CONFIGURED";
        static final String FAILED = "ERROR";
        static final String NOT_CHECKED = "NOT_CHECKED";

        private Risk() {
        }
    }

    private static String riskCell(Part<TrademarkCheckResult> trademark) {
        return switch (trademark.state()) {
            case DONE -> trademark.result().riskLevel().name();
            case NOT_CONFIGURED -> Risk.NOT_CONFIGURED;
            case FAILED -> Risk.FAILED;
        };
    }

    static Measurement.Verdict verdict(Part<TrademarkCheckResult> trademark) {
        if (!trademark.isDone()) {
            return Measurement.Verdict.UNDETERMINED;
        }
        return switch (trademark.result().riskLevel()) {
            case HIGH, MEDIUM -> Measurement.Verdict.FLAGGED;
            case LOW -> Measurement.Verdict.CLEAR;
            case UNKNOWN -> Measurement.Verdict.UNDETERMINED;
        };
    }

    private void countRules(ListingOutcome outcome, CheckResult result) {
        rulesetVersions.add(String.valueOf(result.rulesetVersion()));
        ruleAnalysisVersions.add(String.valueOf(result.analysisVersion()));
        ruleErrors += result.summary().errors();
        ruleWarnings += result.summary().warnings();
        ruleInfos += result.summary().infos();
        if (result.summary().errors() > 0) {
            listingsWithRuleErrors++;
        }
        if (result.summary().warnings() > 0) {
            listingsWithRuleWarnings++;
        }
        Set<String> seen = new TreeSet<>();
        for (CheckResult.FindingView finding : result.findings()) {
            Stat stat = ruleStats.computeIfAbsent(finding.ruleId(), name -> new Stat());
            if (seen.add(finding.ruleId())) {
                stat.listings++;
            }
            stat.add(severityRank(finding.severity()), finding.severity().name());
            writeFinding(outcome, "rules", finding.ruleId(), finding.severity().name(), finding.field(),
                    clip(finding.value()), "", "", finding.message(), finding.suggestion());
        }
    }

    private void countTrademark(ListingOutcome outcome, TrademarkCheckResult result) {
        trademarkRisk.merge(result.riskLevel().name(), 1, Integer::sum);
        if (result.completeness() == Completeness.COMPLETE) {
            complete++;
        } else {
            incomplete++;
        }
        trademarkRulesetVersions.add(result.rulesetVersion());
        catalogVersions.add(result.catalogVersion());
        trademarkAnalysisVersions.add(result.analysisVersion());
        if (disclaimer == null) {
            disclaimer = result.disclaimer();
        }
        Set<String> seen = new TreeSet<>();
        for (TrademarkCheckResult.FindingView finding : result.findings()) {
            if (finding.mark() != null) {
                Stat stat = markStats.computeIfAbsent(finding.mark(), name -> new Stat());
                if (seen.add(finding.mark())) {
                    stat.listings++;
                }
                stat.add(finding.risk().ordinal(), finding.risk().name());
            }
            writeFinding(outcome, "trademark", finding.ruleId(), finding.risk().name(), finding.field(),
                    clip(finding.snippet()), finding.mark() == null ? "" : finding.mark(),
                    String.join("|", finding.sourceIds()), finding.message(), finding.suggestion());
        }
    }

    /** ERROR is the most serious severity; the enum lists it first. */
    private static int severityRank(Severity severity) {
        return Severity.values().length - severity.ordinal();
    }

    private void noteProblem(int line, String id, String message) {
        errorRowsTotal++;
        if (errorRows.size() < ERROR_ROWS_SHOWN) {
            errorRows.add(new ErrorRow(line, id == null ? "" : id, message));
        }
    }

    // ---- CSV

    private void writeListing(ListingOutcome outcome, String status, String risk, String completeness, String ruleErrorsCell,
                              String ruleWarningsCell, String rulesCheckId, String trademarkCheckId, String error) {
        write(listings, outcome.id(), Integer.toString(outcome.line()), status,
                outcome.label() == null ? "" : outcome.label().toString(), risk, completeness, ruleErrorsCell,
                ruleWarningsCell, rulesCheckId, trademarkCheckId, error);
    }

    private void writeFinding(ListingOutcome outcome, String check, String ruleId, String level, String field,
                              String snippet, String mark, String sourceIds, String message, String suggestion) {
        write(findings, outcome.id(), Integer.toString(outcome.line()), check, ruleId, level, field, snippet, mark,
                sourceIds, message, suggestion);
    }

    private static void write(BufferedWriter writer, String... cells) {
        try {
            writer.write(line(cells));
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String line(String[] cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        return line.toString();
    }

    /** One CSV cell: quoted when it needs to be, and defused when a spreadsheet would read it as a formula. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** The text cut to {@link #SNIPPET_MAX_CHARS} characters (never inside a surrogate pair), with "..." when it was cut. */
    static String clip(String value) {
        if (value == null || value.length() <= SNIPPET_MAX_CHARS) {
            return value;
        }
        int end = SNIPPET_MAX_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "...";
    }

    // ---- end

    /** Closes the CSV files and writes {@code summary.html}. */
    void finish(RunInfo info) {
        closeWriters();
        String html = BatchSummaryHtml.render(totals(info), info);
        try {
            Files.writeString(dir.resolve(SUMMARY_FILE), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Totals totals(RunInfo info) {
        return new Totals(rows, unreadable, failed, Map.copyOf(trademarkRisk), complete, incomplete, listingsWithRuleErrors,
                listingsWithRuleWarnings, ruleErrors, ruleWarnings, ruleInfos, ruleStats.size(), markStats.size(),
                top(ruleStats), top(markStats), Set.copyOf(rulesetVersions), Set.copyOf(ruleAnalysisVersions),
                Set.copyOf(trademarkRulesetVersions), Set.copyOf(catalogVersions), Set.copyOf(trademarkAnalysisVersions),
                disclaimer != null ? disclaimer : info.disclaimerFallback(), measurement, List.copyOf(errorRows),
                errorRowsTotal);
    }

    private static List<Ranked> top(Map<String, Stat> stats) {
        return stats.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Stat>>comparingInt(entry -> -entry.getValue().listings)
                        .thenComparingInt(entry -> -entry.getValue().findings)
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP)
                .map(entry -> new Ranked(entry.getKey(), entry.getValue().listings, entry.getValue().findings,
                        entry.getValue().highest))
                .collect(Collectors.toList());
    }

    private void closeWriters() {
        if (writersClosed) {
            return;
        }
        writersClosed = true;
        try {
            findings.close();
            listings.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Closes the CSV files; after an exception the directory has no {@code summary.html}, which marks it unfinished. */
    @Override
    public void close() {
        closeWriters();
    }
}
