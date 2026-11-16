package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.batch.BatchConfigException;
import com.thanhdatpb.listingguard.api.batch.BatchCsvReader;
import com.thanhdatpb.listingguard.api.batch.BatchMapping;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.evaluation.Rate;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the rule check and the trademark scan over a file of listings that carry a label (was the listing claimed or taken
 * down, what about, which mark) and writes a report of how many claimed listings would have been caught before they were
 * published, why the others were missed, and how often listings nobody claimed are flagged (ADR-032).
 *
 * <p>It reads the file with the batch job's reader and mapping ({@code docs/batch-check.md}), so a mapping written for the
 * batch works here too, and analyses each listing with {@link ListingAnalyzer}: <b>nothing is stored, charged or cached</b>,
 * and it needs no client. A row whose data or labels cannot be read is reported with its line and the backtest goes on; a file
 * that is damaged stops it, and the report written so far says it is unfinished.
 */
public final class BacktestJob {

    private static final Logger log = LoggerFactory.getLogger(BacktestJob.class);
    private static final int PROGRESS_EVERY = 500;

    /** What the deployment serves for the trademark part, for the head of the report. */
    public record TrademarkInfo(String state, String catalogVersion, String rulesetVersion, String note) {
    }

    /** What a finished run reports to its caller. */
    public record Result(int rows, int claimed, int caught, int missed, Path reportDir, Rate recall, Rate unclaimedFlagged) {
    }

    private final ListingAnalyzer analyzer;
    private final Function<String, RulesetConfig> rulesets;
    private final Function<String, TrademarkInfo> trademark;
    private final PrintStream out;
    private final Clock clock;

    /**
     * @param rulesets  the active listing-rules ruleset of a marketplace code, the one {@code analyzer} runs
     * @param trademark what is served for the trademark part of a marketplace code
     */
    public BacktestJob(ListingAnalyzer analyzer, Function<String, RulesetConfig> rulesets,
                       Function<String, TrademarkInfo> trademark, PrintStream out, Clock clock) {
        this.analyzer = analyzer;
        this.rulesets = rulesets;
        this.trademark = trademark;
        this.out = out;
        this.clock = clock;
    }

    /**
     * @throws BatchConfigException  before any row is read, if the options, the mapping, the label columns or the file
     *                               cannot be used
     * @throws IllegalStateException after the report is written, if the file turned out to be damaged
     */
    public Result run(BacktestOptions options) {
        String marketplace = options.marketplace().trim().toUpperCase(Locale.ROOT);
        BatchMapping mapping = BatchMapping.load(options.mapping(), marketplace);
        if (!Files.isRegularFile(options.input())) {
            throw new BatchConfigException("The input file " + options.input() + " does not exist or is not a file.");
        }
        String inputSha256 = sha256(options.input());
        RulesetConfig ruleset = rulesets.apply(marketplace);
        Map<String, String> statuses = new TreeMap<>();
        ruleset.rules().forEach((id, settings) -> statuses.put(id, settings.source().status().name()));
        TrademarkInfo served = trademark.apply(marketplace);

        BacktestReport report = new BacktestReport(statuses);
        int rows = 0;
        boolean stoppedAtMaxRows = false;
        String abortReason = null;
        int lastLine;
        BacktestLabels labels;
        try (BatchCsvReader reader = BatchCsvReader.open(options.input(), mapping.csv())) {
            BatchMapping.Bound bound;
            try {
                bound = mapping.bind(reader.header(), null);
                labels = BacktestLabels.bind(reader.header(), options.claimedColumn(), options.issueColumn(),
                        options.markColumn());
            } catch (BatchConfigException e) {
                throw new BatchConfigException("Mapping " + options.mapping() + ": " + e.getMessage(), e);
            }
            lastLine = reader.headerLine();
            try {
                while (options.maxRows() == null || rows < options.maxRows()) {
                    BatchCsvReader.Row row = reader.next();
                    if (row == null) {
                        break;
                    }
                    lastLine = row.line();
                    report.add(assess(row, bound, labels, marketplace, options));
                    rows++;
                    if (rows % PROGRESS_EVERY == 0) {
                        log.info("Backtest: {} rows done", rows);
                    }
                }
                stoppedAtMaxRows = options.maxRows() != null && rows == options.maxRows() && moreRows(reader);
            } catch (BatchCsvReader.FormatException e) {
                abortReason = "the input file " + e.getMessage();
            }
        }

        BacktestReport.RunInfo info = new BacktestReport.RunInfo(
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(), marketplace,
                options.input().getFileName().toString(), inputSha256, options.mapping().getFileName().toString(),
                labels.columns(), ruleset.version(), served.state(), served.catalogVersion(), served.rulesetVersion(),
                served.note(), options.catchSeverity().name(), options.catchTrademarkRisk().name(), options.variantThreshold(),
                options.maxRows(), stoppedAtMaxRows, abortReason);
        BacktestReport.Report written = report.write(options.out(), info);
        BacktestReport.ClaimedSummary claimed = written.claimed();
        out.println("Backtest " + (abortReason == null ? "finished" : "STOPPED (" + abortReason + ")") + ": "
                + written.rows().total() + " rows, " + claimed.total() + " claimed, recall " + claimed.recall().text()
                + ", unclaimed flagged " + written.unclaimed().flaggedShare().text() + ". Report in "
                + options.out().toAbsolutePath() + ".");
        if (!"READY".equals(served.state())) {
            out.println("The trademark part did not run (" + served.state() + "); trademark claims are reported as "
                    + MissReason.TRADEMARK_NOT_SERVED + ".");
        }
        if (abortReason != null) {
            throw new IllegalStateException("The backtest stopped after line " + lastLine + ": " + abortReason
                    + ". The report in " + options.out() + " covers the rows before it and says so.");
        }
        return new Result(written.rows().total(), claimed.total(), claimed.caught(), claimed.missed(), options.out(), claimed.recall(),
                written.unclaimed().flaggedShare());
    }

    private BacktestRow assess(BatchCsvReader.Row row, BatchMapping.Bound bound, BacktestLabels labels, String marketplace,
                               BacktestOptions options) {
        BatchMapping.RowData data;
        try {
            data = bound.read(row.cells());
        } catch (BatchMapping.RowException e) {
            return BacktestRow.unreadable(row.line(), bound.idOrNull(row.cells()), e.getMessage());
        }
        BacktestLabels.Labels label;
        try {
            label = labels.read(row.cells());
        } catch (BacktestLabels.LabelException e) {
            return BacktestRow.labelInvalid(row.line(), data.id(), "Nhãn sai: " + e.getMessage() + ".");
        }
        ListingAnalyzer.Analysis analysis;
        try {
            analysis = analyzer.analyze(marketplace, data.listing());
        } catch (RuntimeException e) {
            log.warn("Backtest: the listing of line {} could not be analysed: {}", row.line(), e.getClass().getSimpleName());
            return BacktestRow.unreadable(row.line(), data.id(), "Không phân tích được listing: " + e.getClass().getSimpleName());
        }
        return BacktestRow.assess(row.line(), data.id(), label, analysis, options);
    }

    private static boolean moreRows(BatchCsvReader reader) {
        try {
            return reader.hasNext();
        } catch (BatchCsvReader.FormatException e) {
            // damage after the rows that were asked for does not matter to this run
            return true;
        }
    }

    /** The SHA-256 of the input file, so a report names the exact file it was made from without holding any of it. */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new BatchConfigException("Cannot read the input file " + file + ": " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
