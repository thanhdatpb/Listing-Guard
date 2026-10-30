package com.thanhdatpb.listingguard.api.batch;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.batch.ListingOutcome.Part;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the rule check and the trademark check over every listing of a CSV file and writes the reports (see
 * {@link BatchReport}). It calls {@link CheckService} and {@link TrademarkCheckService} directly, with no HTTP in
 * between, for the client named on the command line: results are stored under that client with the same cache identity
 * and the same retention as the API, so a listing checked here is answered from the cache by the API afterwards and the
 * other way round. There is no rate limit, because there is no request.
 *
 * <p>A row whose data cannot be read, or whose check fails, is reported with its line and the batch goes on. The two
 * checks are independent: a trademark catalog that is not served ({@code DISABLED} or {@code REFUSED}) leaves the rules
 * running and the report says so. What stops the batch is a file that cannot be read at all (bad bytes, an unclosed
 * quote): the reports written so far are completed, marked as unfinished, and the job fails.
 */
public final class BatchCheckJob {

    private static final Logger log = LoggerFactory.getLogger(BatchCheckJob.class);
    private static final int PROGRESS_EVERY = 100;
    private static final int MESSAGE_MAX_CHARS = 300;

    /** What a finished run reports to its caller. */
    public record Result(int rows, int unreadableRows, int failedRows, boolean stoppedAtMaxRows, Path reportDir) {
    }

    private final ClientRepository clients;
    private final NormalizerRegistry normalizers;
    private final CheckService checks;
    private final TrademarkCheckService trademarkChecks;
    private final TrademarkCatalogRuntime trademark;
    private final TrademarkRulesets trademarkRulesets;
    private final PrintStream out;
    private final Clock clock;

    public BatchCheckJob(ClientRepository clients, NormalizerRegistry normalizers, CheckService checks,
                         TrademarkCheckService trademarkChecks, TrademarkCatalogRuntime trademark,
                         TrademarkRulesets trademarkRulesets, PrintStream out, Clock clock) {
        this.clients = clients;
        this.normalizers = normalizers;
        this.checks = checks;
        this.trademarkChecks = trademarkChecks;
        this.trademark = trademark;
        this.trademarkRulesets = trademarkRulesets;
        this.out = out;
        this.clock = clock;
    }

    /**
     * @throws BatchConfigException  before any listing is checked, if the client, the options, the mapping or the input
     *                               file cannot be used
     * @throws IllegalStateException after the reports are completed, if the input file turned out to be damaged
     */
    public Result run(BatchOptions options) {
        Client client = requireActiveClient(options.client());
        String marketplace = normalizers.get(options.marketplace()).marketplace();
        BatchMapping mapping = BatchMapping.load(options.mapping(), marketplace);
        if (!Files.isRegularFile(options.input())) {
            throw new BatchConfigException("The input file " + options.input() + " does not exist or is not a file.");
        }
        try (BatchCsvReader reader = BatchCsvReader.open(options.input(), mapping.csv())) {
            BatchMapping.Bound bound = bind(options, mapping, reader);
            TrademarkCatalogRuntime.Status status = trademark.status();
            boolean trademarkReady = status.state() == TrademarkCatalogRuntime.State.READY;
            String notReady = trademarkReady ? null : status.state() + ": " + status.detail();

            try (BatchReport report = new BatchReport(options.out())) {
                int rows = 0;
                boolean stoppedAtMaxRows = false;
                String abortReason = null;
                int abortedAfterLine = reader.headerLine();
                try {
                    while (options.maxRows() == null || rows < options.maxRows()) {
                        BatchCsvReader.Row row = reader.next();
                        if (row == null) {
                            break;
                        }
                        abortedAfterLine = row.line();
                        report.add(outcome(row, bound, client.getId(), marketplace, trademarkReady, notReady));
                        rows++;
                        if (rows % PROGRESS_EVERY == 0) {
                            log.info("Batch check: {} rows done", rows);
                        }
                    }
                    stoppedAtMaxRows = options.maxRows() != null && rows == options.maxRows() && moreRows(reader);
                } catch (BatchCsvReader.FormatException e) {
                    abortReason = "the input file " + e.getMessage();
                }
                BatchReport.RunInfo info = new BatchReport.RunInfo(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS),
                        client.getName(), marketplace, options.input().getFileName().toString(),
                        options.mapping().getFileName().toString(), options.labelColumn(), options.maxRows(),
                        stoppedAtMaxRows, abortReason, trademarkReady, notReady,
                        trademarkRulesets.ruleset().disclaimer());
                report.finish(info);
                BatchReport.Totals totals = report.totals(info);
                out.println("Batch check " + (abortReason == null ? "finished" : "STOPPED (" + abortReason + ")") + ": "
                        + totals.rows() + " rows, " + totals.unreadable() + " unreadable, " + totals.failed()
                        + " with a failed check. Reports in " + options.out().toAbsolutePath() + ".");
                if (!trademarkReady) {
                    out.println("Trademark checks were not run (" + notReady + "); the rule checks were.");
                }
                if (abortReason != null) {
                    throw new IllegalStateException("The batch stopped after line " + abortedAfterLine + ": " + abortReason
                            + ". The reports in " + options.out() + " cover the rows before it and say so.");
                }
                return new Result(totals.rows(), totals.unreadable(), totals.failed(), stoppedAtMaxRows, options.out());
            }
        }
    }

    private Client requireActiveClient(String name) {
        Client client = clients.findByName(name).orElseThrow(() -> new BatchConfigException("No client named '" + name
                + "'. Create it with: docker compose run --rm admin client create <name>"));
        if (!client.isActive()) {
            throw new BatchConfigException("Client '" + name + "' is inactive. Activate it with: "
                    + "docker compose run --rm admin client activate " + name);
        }
        return client;
    }

    private static BatchMapping.Bound bind(BatchOptions options, BatchMapping mapping, BatchCsvReader reader) {
        try {
            return mapping.bind(reader.header(), options.labelColumn());
        } catch (BatchConfigException e) {
            throw new BatchConfigException("Mapping " + options.mapping() + ": " + e.getMessage(), e);
        }
    }

    private static boolean moreRows(BatchCsvReader reader) {
        try {
            return reader.hasNext();
        } catch (BatchCsvReader.FormatException e) {
            // damage after the rows that were asked for does not matter to this run
            return true;
        }
    }

    private ListingOutcome outcome(BatchCsvReader.Row row, BatchMapping.Bound bound, UUID clientId, String marketplace,
                                   boolean trademarkReady, String notReady) {
        BatchMapping.RowData data;
        try {
            data = bound.read(row.cells());
        } catch (BatchMapping.RowException e) {
            return ListingOutcome.unreadable(row.line(), bound.idOrNull(row.cells()), e.getMessage());
        }
        Part<CheckResult> rules = rules(row, clientId, marketplace, data.listing());
        Part<TrademarkCheckResult> trademarkPart = trademarkReady
                ? trademark(row, clientId, marketplace, data.listing())
                : Part.notConfigured(notReady);
        return new ListingOutcome(row.line(), data.id(), data.label(), null, rules, trademarkPart);
    }

    private Part<CheckResult> rules(BatchCsvReader.Row row, UUID clientId, String marketplace, ObjectNode listing) {
        try {
            return Part.done(checks.check(clientId, new CheckRequest(marketplace, listing)));
        } catch (RuntimeException e) {
            log.warn("Batch check: the rule check of line {} failed: {}", row.line(), e.toString());
            return Part.failed(describe(e));
        }
    }

    private Part<TrademarkCheckResult> trademark(BatchCsvReader.Row row, UUID clientId, String marketplace,
                                                 ObjectNode listing) {
        try {
            return Part.done(trademarkChecks.check(clientId, new CheckRequest(marketplace, listing)));
        } catch (TrademarkUnavailableException e) {
            // the catalog was switched off or refused after the batch began
            return Part.notConfigured(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Batch check: the trademark check of line {} failed: {}", row.line(), e.toString());
            return Part.failed(describe(e));
        }
    }

    /** The kind of failure and the first line of its message, cut; it goes into a report, so it stays short. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int end = message.indexOf('\n');
        String firstLine = end < 0 ? message : message.substring(0, end);
        if (firstLine.length() > MESSAGE_MAX_CHARS) {
            firstLine = firstLine.substring(0, MESSAGE_MAX_CHARS) + "...";
        }
        return e.getClass().getSimpleName() + (firstLine.isEmpty() ? "" : ": " + firstLine);
    }
}
