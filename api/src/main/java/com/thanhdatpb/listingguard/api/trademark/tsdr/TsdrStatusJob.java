package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.CallResult;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Candidate;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Mode;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Row;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.RunStatus;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Source;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.StatusClass;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code tsdr-status} job (ADR-009, "TSDR status check"): asks the USPTO TSDR for the status of marks of the served catalog
 * and reports the ones that recent findings still name although they are dead. It never edits a catalog, never activates a
 * version and never changes a finding: the report only proposes leaving a mark out at the next sync.
 *
 * <ul>
 *   <li><b>Live run</b>: picks marks of the served catalog (a source id {@code uspto:<8 digits>}), the most named by recent
 *       findings first (it reads the source ids and the dates of retained checks, never their text), skips those asked about
 *       within the configured intervals, and asks the TSDR for at most {@code max-marks-per-run} of them, slowly.</li>
 *   <li><b>Offline run</b> ({@code --input-dir}): reads TSDR documents the operator saved by hand (JSON or XML, named
 *       {@code <serial>.xml}, {@code sn<serial>.json}, ...) and stores their status. No network, no key.</li>
 *   <li>One run at a time (an advisory lock), so two runs never ask about the same mark.</li>
 * </ul>
 */
public class TsdrStatusJob {

    private static final Logger log = LoggerFactory.getLogger(TsdrStatusJob.class);

    /** The options of a run. {@code maxMarks} can only lower the setting. */
    public record Options(String inputDir, Integer maxMarks, boolean dryRun) {
    }

    /** The catalog version the API serves. */
    public record ServedCatalog(long id, String version) {
    }

    /**
     * @param runId          null for a dry run, which writes nothing
     * @param needsAttention the run could not finish what it started (a refused key, a limit that lasts, a lock held): exit non-zero
     * @param reportDir      the folder of the report, or null when none was written
     */
    public record Result(Long runId, RunStatus status, String message, List<String> notes, boolean needsAttention,
                         Path reportDir) {
    }

    private static final Pattern OFFLINE_NAME = Pattern.compile("(?i)(?:sn)?([0-9]{8})\\.(xml|json)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final TsdrProperties properties;
    private final TrademarkProperties trademark;
    private final UsptoStatusMap statusMap;
    private final TsdrStatusStore store;
    private final TsdrStatusLock lock;
    private final Supplier<ServedCatalog> served;
    private final TsdrClient client;
    private final Clock clock;
    private final TsdrStatusParser parser;

    /** @param client null when no call may be made (an offline run, a dry run, or the calls are off) */
    public TsdrStatusJob(TsdrProperties properties, TrademarkProperties trademark, UsptoStatusMap statusMap,
                         TsdrStatusStore store, TsdrStatusLock lock, Supplier<ServedCatalog> served, TsdrClient client,
                         Clock clock) {
        this.properties = properties;
        this.trademark = trademark;
        this.statusMap = statusMap;
        this.store = store;
        this.lock = lock;
        this.served = served;
        this.client = client;
        this.clock = clock;
        this.parser = new TsdrStatusParser(properties.fields());
    }

    public Result run(Options options) throws IOException {
        Mode mode = options.inputDir() != null ? Mode.OFFLINE : Mode.LIVE;
        int limit = properties.maxMarksPerRun();
        if (options.maxMarks() != null) {
            if (options.maxMarks() < 1) {
                throw new IllegalArgumentException("--max-marks must be at least 1.");
            }
            limit = Math.min(limit, options.maxMarks());
        }
        if (mode == Mode.LIVE && !options.dryRun() && client == null) {
            throw new IllegalArgumentException("A live run asks the TSDR: set listing-guard.trademark.tsdr.download.enabled=true "
                    + "and LISTING_GUARD_TSDR_API_KEY, or give --input-dir=<folder inside catalogs/raw> with TSDR files you "
                    + "saved yourself, or use --dry-run to only list the marks.");
        }
        Path input = mode == Mode.OFFLINE ? inputFolder(options.inputDir()) : null;
        ServedCatalog catalog = served.get();

        var held = lock.tryAcquire();
        if (held.isEmpty()) {
            return new Result(null, RunStatus.FAILED, "Another tsdr-status run holds the lock; this one did nothing.",
                    List.of(), true, null);
        }
        try (var ignored = held.get()) {
            return mode == Mode.OFFLINE ? offline(input, catalog, options.dryRun()) : live(catalog, limit, options.dryRun());
        }
    }

    // --- live ---

    private Result live(ServedCatalog catalog, int limit, boolean dryRun) throws IOException {
        Instant now = clock.instant();
        Instant matchedFrom = now.minus(properties.matchWindow());
        Instant answeredCutoff = now.minus(properties.recheckInterval());
        Instant failedCutoff = now.minus(properties.errorRetryInterval());

        Map<String, Candidate> picked = new LinkedHashMap<>();
        for (Candidate c : store.selectMatched(catalog.id(), now, matchedFrom, answeredCutoff, failedCutoff, limit)) {
            picked.putIfAbsent(c.serial(), c);
        }
        int matched = picked.size();
        int room = Math.min(properties.unmatchedPerRun(), limit - matched);
        if (room > 0) {
            for (Candidate c : store.selectUnmatched(catalog.id(), answeredCutoff, failedCutoff, room + matched)) {
                if (picked.size() - matched >= room) {
                    break;
                }
                picked.putIfAbsent(c.serial(), c);
            }
        }
        List<Candidate> selected = List.copyOf(picked.values());
        List<String> notes = new ArrayList<>();
        notes.add("Catalog " + catalog.version() + ": " + selected.size() + " mark(s) to ask about (" + matched
                + " named by findings of the last " + properties.matchWindow() + ", " + (selected.size() - matched)
                + " not), limit " + limit + ".");
        if (dryRun) {
            selected.forEach(c -> notes.add("  would ask: uspto:" + c.serial() + " (" + c.matches() + " finding(s))"));
            return new Result(null, RunStatus.DONE, "Dry run: nothing was asked and nothing was written.", notes, false, null);
        }

        long runId = store.startRun(Mode.LIVE, catalog.id(), catalog.version(), now);
        int recorded = 0;
        int failed = 0;
        int consecutive = 0;
        boolean stopped = false;
        String stopReason = null;
        try {
            for (Candidate c : selected) {
                TsdrClient.Reply reply = client.fetch(c.serial());
                Row row;
                if (reply instanceof TsdrClient.Body body) {
                    row = fromBody(c.serial(), body);
                } else {
                    TsdrClient.Failure failure = (TsdrClient.Failure) reply;
                    row = new Row(c.serial(), Source.API, failure.result(), failure.httpStatus(), failure.attempts(), null, null,
                            null, null, null, null, clock.instant(), failure.message());
                }
                store.insert(runId, row);
                recorded++;
                boolean answered = row.result() == CallResult.OK || row.result() == CallResult.NOT_FOUND;
                if (answered) {
                    consecutive = 0;
                } else {
                    failed++;
                    consecutive++;
                    log.warn("tsdr-status: uspto:{} {} ({})", c.serial(), row.result(), row.error());
                }
                if (reply instanceof TsdrClient.Failure failure && failure.stopsRun()) {
                    stopped = true;
                    stopReason = failure.message();
                    break;
                }
                if (consecutive >= properties.maxConsecutiveFailures()) {
                    stopped = true;
                    stopReason = consecutive + " marks in a row failed; the TSDR may be down or the settings wrong.";
                    break;
                }
            }
        } catch (RuntimeException e) {
            store.finishRun(runId, RunStatus.FAILED, selected.size(), recorded, failed, "Failed: " + e.getClass().getSimpleName(),
                    clock.instant());
            throw e;
        }
        if (stopped) {
            notes.add("The run stopped early: " + stopReason);
        }
        RunStatus status = stopped ? RunStatus.STOPPED : failed > 0 ? RunStatus.INCOMPLETE : RunStatus.DONE;
        return finish(runId, Mode.LIVE, status, catalog, selected.size(), recorded, failed, notes, stopped);
    }

    private Row fromBody(String serial, TsdrClient.Body body) {
        Instant at = clock.instant();
        String sha = sha256(body.bytes());
        try {
            TsdrStatusParser.Parsed parsed = parser.parse(body.bytes());
            return new Row(serial, Source.API, CallResult.OK, 200, body.attempts(), parsed.code(), parsed.description(),
                    parsed.date(), classify(parsed.code()), statusMap.version(), sha, at, null);
        } catch (TsdrStatusParser.Unparseable e) {
            return new Row(serial, Source.API, CallResult.UNPARSEABLE, 200, body.attempts(), null, null, null, null, null, sha,
                    at, e.getMessage());
        }
    }

    // --- offline ---

    private Result offline(Path input, ServedCatalog catalog, boolean dryRun) throws IOException {
        Instant now = clock.instant();
        List<Path> files;
        try (Stream<Path> list = Files.list(input)) {
            files = list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString())).toList();
        }
        List<String> notes = new ArrayList<>();
        Long runId = dryRun ? null : store.startRun(Mode.OFFLINE, catalog.id(), catalog.version(), now);
        int selected = 0;
        int recorded = 0;
        int failed = 0;
        int unchanged = 0;
        long max = properties.download().maxResponseSize().toBytes();
        try {
            for (Path file : files) {
                String name = file.getFileName().toString();
                Matcher m = OFFLINE_NAME.matcher(name);
                if (!m.matches()) {
                    notes.add("Left out " + name + ": the name is not <8 digits>.xml|json or sn<8 digits>.xml|json.");
                    continue;
                }
                selected++;
                String serial = m.group(1);
                Row row;
                if (Files.size(file) > max) {
                    row = new Row(serial, Source.FILE, CallResult.UNPARSEABLE, null, null, null, null, null, null, null, null,
                            clock.instant(), "The file is larger than max-response-size (" + max + " bytes).");
                } else {
                    byte[] bytes = Files.readAllBytes(file);
                    String sha = sha256(bytes);
                    if (store.hasFile(serial, sha)) {
                        unchanged++;
                        continue;
                    }
                    try {
                        TsdrStatusParser.Parsed parsed = parser.parse(bytes);
                        row = new Row(serial, Source.FILE, CallResult.OK, null, null, parsed.code(), parsed.description(),
                                parsed.date(), classify(parsed.code()), statusMap.version(), sha, clock.instant(), null);
                    } catch (TsdrStatusParser.Unparseable e) {
                        row = new Row(serial, Source.FILE, CallResult.UNPARSEABLE, null, null, null, null, null, null, null,
                                sha, clock.instant(), e.getMessage());
                    }
                }
                if (row.result() != CallResult.OK) {
                    failed++;
                    notes.add("Could not read " + name + ": " + row.error());
                }
                if (runId != null) {
                    store.insert(runId, row);
                    recorded++;
                } else {
                    notes.add("  would read " + name);
                }
            }
        } catch (RuntimeException | IOException e) {
            if (runId != null) {
                store.finishRun(runId, RunStatus.FAILED, selected, recorded, failed, "Failed: " + e.getClass().getSimpleName(),
                        clock.instant());
            }
            throw e;
        }
        notes.add(0, files.size() + " file(s) in the folder: " + selected + " named like a TSDR file, " + unchanged
                + " already stored unchanged, " + recorded + " stored.");
        if (runId == null) {
            return new Result(null, RunStatus.DONE, "Dry run: nothing was read into the database.", notes, false, null);
        }
        RunStatus status = failed > 0 ? RunStatus.INCOMPLETE : RunStatus.DONE;
        return finish(runId, Mode.OFFLINE, status, catalog, selected, recorded, failed, notes, false);
    }

    // --- common ---

    private Result finish(long runId, Mode mode, RunStatus status, ServedCatalog catalog, int selected, int recorded,
                          int failed, List<String> notes, boolean stopped) {
        Instant now = clock.instant();
        Instant matchedFrom = now.minus(properties.matchWindow());
        List<TsdrStatusStore.MarkReport> marks = store.matchedMarks(catalog.id(), now, matchedFrom);
        Path outDir = reportsDir();
        Path dir = null;
        String message = status.name() + ": " + recorded + " answer(s) stored, " + failed + " failed, "
                + TsdrStatusReport.dead(marks).size() + " dead mark(s) still named by findings.";
        try {
            dir = TsdrStatusReport.write(outDir.resolve(STAMP.format(now)), new TsdrStatusReport.Data(now, runId, mode,
                    catalog.version(), matchedFrom, now, statusMap.version(), selected, recorded, failed, stopped, marks,
                    notes, properties));
            TsdrStatusReport.prune(outDir, properties.keepReports());
        } catch (IOException e) {
            notes.add("The report could not be written: " + e.getClass().getSimpleName());
            status = status == RunStatus.DONE ? RunStatus.INCOMPLETE : status;
        }
        store.finishRun(runId, status, selected, recorded, failed, message, now);
        return new Result(runId, status, message, notes, status == RunStatus.STOPPED, dir);
    }

    /** The class of a status code, from the status map and nothing else; a code the map does not settle is UNKNOWN. */
    StatusClass classify(String code) {
        String normalized = code.matches("[0-9]{1,2}") ? "0".repeat(3 - code.length()) + code : code;
        if (statusMap.outcome(normalized) instanceof UsptoStatusMap.Mapped mapped) {
            MarkStatus status = mapped.status();
            return status == MarkStatus.REGISTERED || status == MarkStatus.PENDING ? StatusClass.LIVE : StatusClass.DEAD;
        }
        return StatusClass.UNKNOWN;
    }

    private Path catalogsDir() {
        return Path.of(trademark.catalogsDir()).toAbsolutePath().normalize();
    }

    private Path tsdrDir() {
        Path catalogs = catalogsDir();
        Path dir = catalogs.resolve(properties.dir()).normalize();
        if (!dir.startsWith(catalogs) || dir.equals(catalogs)) {
            throw new IllegalArgumentException("listing-guard.trademark.tsdr.dir must be a folder inside " + catalogs + ".");
        }
        return dir;
    }

    private Path reportsDir() {
        return tsdrDir().resolve("out");
    }

    private Path inputFolder(String inputDir) {
        Path raw = catalogsDir().resolve("raw").normalize();
        Path input = raw.resolve(inputDir).normalize();
        if (!input.startsWith(raw) || input.equals(raw)) {
            throw new IllegalArgumentException("--input-dir must be a folder inside " + raw + ", got '" + inputDir + "'.");
        }
        if (input.startsWith(tsdrDir())) {
            throw new IllegalArgumentException("--input-dir must not be inside the report folder " + tsdrDir()
                    + "; put the TSDR files you saved in a folder of their own.");
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("--input-dir is not a folder: " + raw.relativize(input));
        }
        return input;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
