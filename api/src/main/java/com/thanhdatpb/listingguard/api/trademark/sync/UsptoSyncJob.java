package com.thanhdatpb.listingguard.api.trademark.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Activation;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.CatalogVersion;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogLock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.UsptoConvertProperties;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoBulkClient.DownloadException;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoBulkClient.RemoteFile;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.FileStatus;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Kind;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Mode;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Origin;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.RunStatus;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.RunSummary;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.SyncFile;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.ConversionReport;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchive;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchiveException;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoCatalogConverter;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConvertFilters;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One run of the {@code uspto-sync} job (ADR-009, PR A2d). In order:
 * <ol>
 *   <li>takes the catalog lock ({@link TrademarkCatalogLock}; another run or an admin catalog command means this one
 *       changes nothing) and marks a run a crash left {@code RUNNING} as failed;</li>
 *   <li>gets new files: from a folder of {@code catalogs/raw} ({@code --input-dir}, no network), from the ODP when the
 *       download is on, or none (it then rebuilds from the store when its inputs changed);</li>
 *   <li>chooses the inputs: the newest complete backfile set and every stored daily file after its last day; a daily file
 *       after it that is missing or failed blocks the run;</li>
 *   <li>converts them in one strict A2c run into {@code catalog/<label>/raw/<label>.jsonl}, writes
 *       {@code catalog/<label>/manifest.yaml} and imports the catalog that manifest names; inputs it converted before reuse
 *       the catalog version they made;</li>
 *   <li>activates the version unless told not to, after a rollback, or when it has far fewer entries than the served one;</li>
 *   <li>deletes the catalog versions it made beyond {@code keep-versions}, never the served one or one served within
 *       {@code prune-grace}.</li>
 * </ol>
 * It never logs the API key, a mark or a serial number.
 */
public class UsptoSyncJob {

    /** What the operator asked for. */
    public record Options(String inputDir, boolean backfile, LocalDate backfileThrough, boolean noActivate,
                          boolean forceActivate, boolean dryRun, List<String> ignoreFiles, String reason,
                          Integer maxFiles) {
        public Options {
            ignoreFiles = ignoreFiles == null ? List.of() : List.copyOf(ignoreFiles);
        }

        public static Options defaults() {
            return new Options(null, false, null, false, false, false, List.of(), null, null);
        }
    }

    /**
     * What a run did.
     *
     * @param runId          null for a dry run, which records nothing
     * @param needsAttention true when the operator must act (a missing file, a held activation of a smaller catalog, a
     *                       stopped download); the job then ends with a non-zero code
     */
    public record Result(Long runId, RunStatus status, String catalogVersion, String message, List<String> notes,
                         boolean needsAttention) {
        public Result {
            notes = List.copyOf(notes);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(UsptoSyncJob.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int LISTED_NAMES = 10;
    private static final Set<FileStatus> NOT_YET = EnumSet.of(FileStatus.PENDING, FileStatus.FAILED);

    private final UsptoSyncProperties properties;
    private final UsptoConvertProperties convert;
    private final TrademarkProperties trademark;
    private final TrademarkRulesets rulesets;
    private final ResourceLoader resources;
    private final UsptoSyncStore store;
    private final TrademarkCatalogImporter importer;
    private final CatalogActivations activations;
    private final TrademarkCatalogLock lock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UsptoBulkClient client;
    private final Clock clock;
    private final UsptoFileNames names;

    /** @param client the ODP client, null when the download is off */
    public UsptoSyncJob(UsptoSyncProperties properties, UsptoConvertProperties convert, TrademarkProperties trademark,
                        TrademarkRulesets rulesets, ResourceLoader resources, UsptoSyncStore store,
                        TrademarkCatalogImporter importer, CatalogActivations activations, TrademarkCatalogLock lock,
                        JdbcTemplate jdbc, TransactionTemplate transactions, UsptoBulkClient client, Clock clock) {
        this.properties = properties;
        this.convert = convert;
        this.trademark = trademark;
        this.rulesets = rulesets;
        this.resources = resources;
        this.store = store;
        this.importer = importer;
        this.activations = activations;
        this.lock = lock;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.client = client;
        this.clock = clock;
        this.names = new UsptoFileNames(properties.dailyNamePattern(), properties.backfileNamePattern());
    }

    /** The state of one run while it runs. */
    private static final class RunState {
        private final long id;
        private final Mode mode;
        private final Options options;
        private final List<String> notes = new ArrayList<>();
        private int added;
        private int failed;
        private String stopped;
        private String attention;

        RunState(long id, Mode mode, Options options) {
            this.id = id;
            this.mode = mode;
            this.options = options;
        }

        void note(String text) {
            notes.add(text);
            log.info("{}", text);
        }

        void warn(String text) {
            notes.add(text);
            log.warn("{}", text);
        }
    }

    /**
     * @param status            the outcome of the run
     * @param catalogVersionId  the version made or reused, null when none
     */
    private record Outcome(RunStatus status, String message, boolean attention, Integer inputFiles, String digest,
                           ConversionReport report, Long catalogVersionId, String catalogVersion) {

        static Outcome of(RunStatus status, String message, boolean attention) {
            return new Outcome(status, message, attention, null, null, null, null, null);
        }

        Outcome with(RunStatus newStatus, String newMessage, boolean newAttention) {
            return new Outcome(newStatus, newMessage, newAttention, inputFiles, digest, report, catalogVersionId,
                    catalogVersion);
        }
    }

    /** The files one conversion reads. */
    private record Inputs(LocalDate through, List<SyncFile> backfile, List<SyncFile> dailies, String blocked) {

        List<SyncFile> all() {
            List<SyncFile> all = new ArrayList<>(backfile);
            all.addAll(dailies);
            return all;
        }

        LocalDate dataDate() {
            LocalDate latest = through;
            for (SyncFile daily : dailies) {
                if (latest == null || daily.dataDate().isAfter(latest)) {
                    latest = daily.dataDate();
                }
            }
            return latest;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Runs the job once.
     *
     * @throws IllegalArgumentException for a refused option or input (nothing was changed)
     * @throws IllegalStateException    when another run holds the lock, a stored file changed, the disk is too full
     * @throws IOException              when a file cannot be read or written
     */
    public Result run(Options options) throws IOException {
        Mode mode = options.inputDir() != null ? Mode.OFFLINE
                : properties.download().enabled() ? Mode.DOWNLOAD : Mode.STORE;
        validate(options, mode);
        if (options.dryRun()) {
            return dryRun(mode, options);
        }
        TrademarkCatalogLock.Held held = lock.tryAcquire().orElseThrow(() -> new IllegalStateException(
                "Another uspto-sync run or a catalog admin command holds the catalog lock; this run changed nothing."));
        try (held) {
            int interrupted = store.failInterruptedRuns();
            RunState state = new RunState(store.startRun(mode, options.backfile()), mode, options);
            if (interrupted > 0) {
                state.warn(interrupted + " earlier run(s) had not finished (a crash or a stop) and are marked FAILED.");
            }
            log.info("uspto-sync run {} ({}{}) started.", state.id, mode, options.backfile() ? ", backfile" : "");
            try {
                Outcome outcome = execute(state);
                store.finishRun(state.id, outcome.status(), summary(state, outcome));
                return new Result(state.id, outcome.status(), outcome.catalogVersion(), outcome.message(), state.notes,
                        outcome.attention());
            } catch (IOException | RuntimeException e) {
                store.finishRun(state.id, RunStatus.FAILED, RunSummary.of(state.added, state.failed,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
                throw e;
            }
        }
    }

    private void validate(Options options, Mode mode) {
        if (!options.dryRun() && properties.catalog().license().isBlank()) {
            throw new IllegalArgumentException("Set the terms of the USPTO data you checked in USPTO_SYNC_LICENSE "
                    + "(listing-guard.trademark.uspto.sync.catalog.license), for example 'USPTO bulk data, public domain, "
                    + "checked on 2026-11-12'; it is stored with every catalog version (ADR-009, \"Terms\").");
        }
        if (!options.ignoreFiles().isEmpty() && (options.reason() == null || options.reason().isBlank())) {
            throw new IllegalArgumentException("--ignore-file needs --reason=<why the file is left out>.");
        }
        if (options.backfileThrough() != null && !options.backfile()) {
            throw new IllegalArgumentException("--backfile-through goes with --backfile.");
        }
        if (options.backfileThrough() != null && mode != Mode.OFFLINE) {
            throw new IllegalArgumentException("--backfile-through goes with --input-dir: a downloaded backfile is named by "
                    + "its listing.");
        }
        if (options.backfile() && mode == Mode.STORE) {
            throw new IllegalArgumentException("--backfile needs --input-dir=<folder inside catalogs/raw> (or the download "
                    + "switched on).");
        }
        if (options.maxFiles() != null && options.maxFiles() < 1) {
            throw new IllegalArgumentException("--max-files must be at least 1.");
        }
        if (options.noActivate() && options.forceActivate()) {
            throw new IllegalArgumentException("--no-activate and --force-activate contradict each other.");
        }
    }

    private RunSummary summary(RunState state, Outcome outcome) {
        ConversionReport report = outcome.report();
        return new RunSummary(state.added, state.failed, outcome.inputFiles(), outcome.digest(),
                report == null ? null : report.recordsRead(), report == null ? null : report.recordsWritten(),
                report == null ? null : report.outputSha256(), outcome.catalogVersionId(), outcome.catalogVersion(),
                outcome.message());
    }

    private Outcome execute(RunState state) throws IOException {
        for (String name : state.options.ignoreFiles()) {
            ignore(name, state);
        }
        switch (state.mode) {
            case OFFLINE -> registerOffline(state);
            case DOWNLOAD -> download(state, true);
            case STORE -> state.note("No --input-dir and the download is off: the stored files are converted when they "
                    + "changed.");
        }
        if (state.stopped != null) {
            return Outcome.of(RunStatus.FAILED, "The download stopped: " + state.stopped + " Files fetched before it are "
                    + "kept; the next run continues.", true);
        }
        Inputs inputs = inputs(state, true);
        if (inputs.blocked() != null) {
            return Outcome.of(RunStatus.BLOCKED, inputs.blocked(), true);
        }
        verifyStored(inputs.all());

        UsptoStatusMap statusMap = statusMap();
        UsptoConvertFilters filters = filters();
        NormalizationIdentity identity = NormalizationIdentity.of(rulesets.ruleset());
        String digest = digest(inputs, statusMap, filters, identity);

        Long versionId = null;
        String version = null;
        ConversionReport report = null;
        Optional<UsptoSyncStore.Run> earlier = store.runWithDigest(digest);
        if (earlier.isPresent() && activations.find(earlier.get().catalogVersionId()).isPresent()) {
            versionId = earlier.get().catalogVersionId();
            version = earlier.get().catalogVersion();
            state.note("These inputs were converted before (run " + earlier.get().id() + "): catalog version " + version
                    + " is reused, nothing is converted.");
        } else {
            Converted converted = convertAndImport(inputs, digest, statusMap, filters, identity, state);
            versionId = converted.versionId();
            version = converted.version();
            report = converted.report();
        }
        Outcome outcome = activate(new Outcome(RunStatus.IMPORTED, null, false, inputs.all().size(), digest, report,
                versionId, version), state);
        prune(versionId, state);
        reportSuperseded(inputs.through(), state);
        return outcome;
    }

    // --- ignoring a file ---

    private void ignore(String name, RunState state) {
        LocalDate date = names.daily(name).map(UsptoFileNames.Parsed::date).orElseThrow(() -> new IllegalArgumentException(
                "--ignore-file=" + name + " is not the name of a daily file (daily-name-pattern); only a daily file can be "
                        + "left out."));
        if (!store.ignored(name, date, state.options.reason().strip(), state.id)) {
            throw new IllegalArgumentException("--ignore-file=" + name + ": the file is stored; a stored file cannot be "
                    + "left out.");
        }
        state.warn("The daily file " + name + " is left out on purpose: " + state.options.reason().strip());
    }

    // --- offline: a folder of catalogs/raw ---

    private record Candidate(Path path, String name, LocalDate date, Integer part) {
    }

    private record Checked(Candidate candidate, long size, String sha256) {
    }

    private Path inputFolder(String inputDir) {
        Path raw = rawDir();
        Path input = raw.resolve(inputDir).normalize();
        if (!input.startsWith(raw) || input.equals(raw)) {
            throw new IllegalArgumentException("--input-dir must be a folder inside " + raw + ", got '" + inputDir + "'.");
        }
        if (input.startsWith(syncDir())) {
            throw new IllegalArgumentException("--input-dir must not be inside the sync folder " + syncDir()
                    + "; put the files you downloaded in a folder of their own.");
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("--input-dir is not a folder: " + raw.relativize(input));
        }
        return input;
    }

    private List<Candidate> candidates(Path input, Options options, List<String> notes) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(input)) {
            files = list.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        Kind kind = options.backfile() ? Kind.BACKFILE : Kind.DAILY;
        List<Candidate> candidates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int others = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".zip") && !lower.endsWith(".xml")) {
                others++;
                continue;
            }
            if (!UsptoFileNames.plain(name)) {
                problems.add(printable(name) + " is not a plain file name");
                continue;
            }
            Optional<UsptoFileNames.Parsed> parsed = kind == Kind.DAILY ? names.daily(name) : names.backfile(name);
            if (kind == Kind.BACKFILE && options.backfileThrough() != null) {
                candidates.add(new Candidate(path, name, options.backfileThrough(),
                        parsed.map(UsptoFileNames.Parsed::part).orElse(null)));
            } else if (parsed.isEmpty()) {
                problems.add(name + " does not match " + (kind == Kind.DAILY ? "daily-name-pattern" : "backfile-name-pattern"));
            } else {
                candidates.add(new Candidate(path, name, parsed.get().date(), parsed.get().part()));
            }
        }
        if (others > 0) {
            notes.add(others + " file(s) in the folder are neither .zip nor .xml and are not read.");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("These files cannot be registered as " + (kind == Kind.DAILY ? "daily"
                    : "backfile") + " files: " + String.join("; ", problems) + ". The patterns are settings "
                    + "(listing-guard.trademark.uspto.sync.daily-name-pattern, backfile-name-pattern); --backfile-through="
                    + "YYYY-MM-DD names the last day of a backfile whose names do not say it. Nothing was registered.");
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("--input-dir holds no .zip or .xml file: " + rawDir().relativize(input));
        }
        return candidates;
    }

    private void registerOffline(RunState state) throws IOException {
        Options options = state.options;
        Kind kind = options.backfile() ? Kind.BACKFILE : Kind.DAILY;
        Path input = inputFolder(options.inputDir());
        List<Candidate> candidates = candidates(input, options, state.notes);
        if (kind == Kind.BACKFILE) {
            checkBackfileSet(candidates);
        }
        // every file is checked before any is copied, so a refused file registers nothing
        List<Checked> checked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                checked.add(check(candidate));
            } catch (UsptoArchiveException e) {
                problems.add(e.getMessage());
            }
        }
        List<Checked> toCopy = new ArrayList<>();
        for (Checked file : checked) {
            Optional<SyncFile> known = store.file(kind, file.candidate().name());
            if (known.isPresent() && known.get().status() == FileStatus.STORED) {
                if (!file.sha256().equals(known.get().sha256())) {
                    problems.add(file.candidate().name() + " is stored already with another SHA-256 (stored "
                            + known.get().storedAt() + "); a stored file does not change, so it is refused");
                }
                continue;
            }
            toCopy.add(file);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Nothing was registered: " + String.join("; ", problems) + ".");
        }
        if (kind == Kind.BACKFILE) {
            checkBackfileParts(candidates);
        }
        int already = checked.size() - toCopy.size();
        if (already > 0) {
            state.note(already + " file(s) of " + rawDir().relativize(input) + " are stored already and are not copied "
                    + "again.");
        }
        for (Checked file : toCopy) {
            requireFreeDisk(filesDir(kind), file.size(), "copying " + file.candidate().name());
            copyInto(file.candidate().path(), filesDir(kind).resolve(file.candidate().name()), file.sha256());
        }
        Runnable record = () -> {
            for (Checked file : toCopy) {
                store.stored(kind, file.candidate().name(), file.candidate().date(), Origin.OFFLINE, file.size(),
                        file.sha256(), 0, state.id);
            }
        };
        if (kind == Kind.BACKFILE) {
            // a backfile set appears as a whole or not at all
            transactions.executeWithoutResult(status -> record.run());
        } else {
            record.run();
        }
        state.added += toCopy.size();
        if (!toCopy.isEmpty()) {
            state.note("Registered " + toCopy.size() + " " + (kind == Kind.DAILY ? "daily" : "backfile") + " file(s) from "
                    + rawDir().relativize(input) + "; the originals there can be deleted.");
        }
    }

    /** One backfile set at a time: every file names the same last day. */
    private static void checkBackfileSet(List<Candidate> candidates) {
        Set<LocalDate> days = new java.util.TreeSet<>();
        candidates.forEach(c -> days.add(c.date()));
        if (days.size() > 1) {
            throw new IllegalArgumentException("The files name several last days " + days + "; register one backfile set at "
                    + "a time. Nothing was registered.");
        }
    }

    /** With part numbers, the parts given and those stored for the same last day must run from 1 without a gap. */
    private void checkBackfileParts(List<Candidate> candidates) {
        if (candidates.stream().anyMatch(c -> c.part() == null)) {
            return;
        }
        LocalDate through = candidates.get(0).date();
        Set<Integer> parts = new java.util.TreeSet<>();
        candidates.forEach(c -> parts.add(c.part()));
        for (SyncFile stored : store.files(Kind.BACKFILE)) {
            if (stored.dataDate().equals(through) && stored.status() == FileStatus.STORED) {
                names.backfile(stored.name()).map(UsptoFileNames.Parsed::part).ifPresent(parts::add);
            }
        }
        List<Integer> missing = new ArrayList<>();
        int max = parts.stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int part = 1; part <= max; part++) {
            if (!parts.contains(part)) {
                missing.add(part);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The backfile set up to " + through + " misses the part(s) " + missing
                    + "; give every part (the last part cannot be checked). Nothing was registered.");
        }
    }

    private Checked check(Candidate candidate) throws IOException {
        long size = Files.size(candidate.path());
        if (UsptoArchive.isArchive(candidate.name())) {
            UsptoArchive.inspect(candidate.path(), limits());
        } else if (size > properties.archive().maxEntrySize().toBytes()) {
            throw new UsptoArchiveException(UsptoArchiveException.Kind.UNSAFE, candidate.name() + " is larger than "
                    + "archive.max-entry-size.");
        }
        return new Checked(candidate, size, sha256(candidate.path()));
    }

    private void copyInto(Path source, Path target, String sha256) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(source), digest);
             OutputStream out = Files.newOutputStream(part)) {
            in.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            Files.deleteIfExists(part);
            throw new IllegalStateException(source.getFileName() + " changed while it was copied; nothing was registered "
                    + "for it.");
        }
        move(part, target);
    }

    // --- download ---

    private void download(RunState state, boolean record) throws IOException {
        if (client == null) {
            throw new IllegalStateException("The download is on but no ODP client was made.");
        }
        if (!properties.download().hasKey()) {
            throw new IllegalArgumentException("The download is on (listing-guard.trademark.uspto.sync.download.enabled) but "
                    + "LISTING_GUARD_USPTO_ODP_API_KEY is empty. Set it in .env, or switch the download off and use "
                    + "--input-dir.");
        }
        int budget = state.options.maxFiles() != null ? state.options.maxFiles()
                : properties.download().maxFilesPerRun();
        try {
            if (state.options.backfile()) {
                downloadBackfile(state, budget, record);
            } else {
                downloadDaily(state, budget, record);
            }
        } catch (DownloadException e) {
            // a listing that failed: nothing more can be fetched this run
            state.stopped = e.getMessage();
            state.warn(e.getMessage());
        }
    }

    private void downloadBackfile(RunState state, int budget, boolean record) throws IOException, DownloadException {
        UsptoSyncProperties.Download settings = properties.download();
        UsptoBulkClient.Listing listing = client.list(settings.backfileProduct(), settings.backfileQuery());
        if (!listing.listPresent()) {
            throw new DownloadException(DownloadException.Reason.FAILED, "The backfile listing has no file list at "
                    + settings.filesPointer() + " (download.files-pointer; ADR-009, verification step 2).", 1);
        }
        TreeMap<LocalDate, List<RemoteFile>> sets = new TreeMap<>();
        int skipped = listing.skipped();
        for (RemoteFile file : listing.files()) {
            Optional<UsptoFileNames.Parsed> parsed = names.backfile(file.name());
            if (parsed.isEmpty()) {
                skipped++;
                continue;
            }
            sets.computeIfAbsent(parsed.get().date(), d -> new ArrayList<>()).add(file);
        }
        if (skipped > 0) {
            state.note(skipped + " entr(ies) of the backfile listing do not match backfile-name-pattern and are skipped.");
        }
        if (sets.isEmpty()) {
            throw new DownloadException(DownloadException.Reason.FAILED, "The backfile listing names no file that matches "
                    + "backfile-name-pattern.", 1);
        }
        LocalDate through = sets.lastKey();
        List<RemoteFile> set = sets.lastEntry().getValue();
        state.note("The backfile listing offers a set up to " + through + " of " + set.size() + " file(s).");
        if (!record) {
            describeMissing(Kind.BACKFILE, set, state);
            return;
        }
        Map<String, RemoteFile> remote = new LinkedHashMap<>();
        for (RemoteFile file : set) {
            remote.put(file.name(), file);
            store.listed(Kind.BACKFILE, file.name(), through, file.size(), file.checksum(), state.id);
        }
        List<SyncFile> wanted = store.files(Kind.BACKFILE).stream()
                .filter(f -> f.dataDate().equals(through) && NOT_YET.contains(f.status())).toList();
        fetch(state, Kind.BACKFILE, settings.backfileProduct(), wanted, remote, budget);
    }

    private void downloadDaily(RunState state, int budget, boolean record) throws IOException, DownloadException {
        UsptoSyncProperties.Download settings = properties.download();
        LocalDate through = activeBackfile().map(BackfileSet::through).orElse(null);
        if (through == null) {
            if (properties.requireBackfile()) {
                state.warn("No complete backfile set is stored, so no daily file is downloaded; load a backfile first "
                        + "(--backfile).");
                return;
            }
            through = dailyFrom().minusDays(1);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = through.plusDays(1);
        Map<String, RemoteFile> remote = new LinkedHashMap<>();
        int windows = 0;
        int withoutList = 0;
        int skipped = 0;
        for (LocalDate start = from; !start.isAfter(today); ) {
            LocalDate end = start.plusDays(settings.listWindowDays() - 1L);
            if (end.isAfter(today)) {
                end = today;
            }
            String query = settings.dailyQuery().replace("{from}", start.toString()).replace("{to}", end.toString());
            UsptoBulkClient.Listing listing = client.list(settings.dailyProduct(), query);
            windows++;
            if (!listing.listPresent()) {
                withoutList++;
            }
            skipped += listing.skipped();
            for (RemoteFile file : listing.files()) {
                Optional<UsptoFileNames.Parsed> parsed = names.daily(file.name());
                if (parsed.isEmpty()) {
                    skipped++;
                } else if (parsed.get().date().isAfter(through)) {
                    remote.put(file.name(), file);
                }
            }
            start = end.plusDays(1);
        }
        if (skipped > 0) {
            state.note(skipped + " entr(ies) of the daily listings do not match daily-name-pattern and are skipped.");
        }
        if (windows > 0 && withoutList == windows && ChronoUnit.DAYS.between(from, today) > 7) {
            state.attention = "No listing of " + settings.dailyProduct() + " from " + from + " to " + today + " had a file "
                    + "list at " + settings.filesPointer() + "; check download.files-pointer and download.daily-query "
                    + "(ADR-009, verification step 2).";
            state.warn(state.attention);
        }
        state.note("The daily listings from " + from + " to " + today + " name " + remote.size() + " file(s).");
        if (!record) {
            describeMissing(Kind.DAILY, List.copyOf(remote.values()), state);
            return;
        }
        for (RemoteFile file : remote.values()) {
            store.listed(Kind.DAILY, file.name(), names.daily(file.name()).orElseThrow().date(), file.size(),
                    file.checksum(), state.id);
        }
        LocalDate after = through;
        List<SyncFile> wanted = store.files(Kind.DAILY).stream()
                .filter(f -> f.dataDate().isAfter(after) && NOT_YET.contains(f.status())).toList();
        fetch(state, Kind.DAILY, settings.dailyProduct(), wanted, remote, budget);
    }

    private void describeMissing(Kind kind, List<RemoteFile> offered, RunState state) {
        List<String> missing = new ArrayList<>();
        for (RemoteFile file : offered) {
            Optional<SyncFile> known = store.file(kind, file.name());
            if (known.isEmpty() || known.get().status() != FileStatus.STORED) {
                missing.add(file.name() + (file.size() == null ? "" : " (" + file.size() + " bytes)"));
            }
        }
        state.note(missing.size() + " of them are not stored" + (missing.isEmpty() ? "." : ": "
                + String.join(", ", missing.subList(0, Math.min(LISTED_NAMES, missing.size())))
                + (missing.size() > LISTED_NAMES ? ", ..." : "")));
    }

    private void fetch(RunState state, Kind kind, String product, List<SyncFile> wanted, Map<String, RemoteFile> remote,
                       int budget) throws IOException {
        int left = budget;
        for (int i = 0; i < wanted.size(); i++) {
            SyncFile row = wanted.get(i);
            if (left <= 0) {
                state.warn("max-files-per-run reached; the next run continues with " + (wanted.size() - i)
                        + " more file(s).");
                return;
            }
            left--;
            RemoteFile file = remote.getOrDefault(row.name(),
                    new RemoteFile(row.name(), row.sourceSizeBytes(), row.sourceChecksum(), null));
            Path target = filesDir(kind).resolve(row.name());
            requireFreeDisk(filesDir(kind), file.size() == null ? 0 : file.size(), "downloading " + row.name());
            try {
                UsptoBulkClient.Downloaded downloaded = client.download(product, file, target, path -> {
                    if (UsptoArchive.isArchive(row.name())) {
                        UsptoArchive.inspect(path, limits());
                    }
                });
                store.stored(kind, row.name(), row.dataDate(), Origin.DOWNLOAD, downloaded.sizeBytes(), downloaded.sha256(),
                        downloaded.attempts(), state.id);
                state.added++;
                state.note("Downloaded " + row.name() + " (" + downloaded.sizeBytes() + " bytes"
                        + (downloaded.attempts() > 1 ? ", " + downloaded.attempts() + " attempts" : "") + ").");
            } catch (DownloadException e) {
                store.failed(kind, row.name(), e.attempts(), e.getMessage());
                state.failed++;
                state.warn(e.getMessage());
                if (e.stopsRun()) {
                    state.stopped = e.getMessage();
                    return;
                }
            }
        }
    }

    // --- the inputs ---

    /** A backfile set: its last day and its stored files. */
    private record BackfileSet(LocalDate through, List<SyncFile> files) {
    }

    /** The newest backfile set with stored files and none pending or failed. */
    private Optional<BackfileSet> activeBackfile() {
        TreeMap<LocalDate, List<SyncFile>> sets = new TreeMap<>();
        for (SyncFile file : store.files(Kind.BACKFILE)) {
            if (file.status() != FileStatus.REMOVED) {
                sets.computeIfAbsent(file.dataDate(), d -> new ArrayList<>()).add(file);
            }
        }
        for (Map.Entry<LocalDate, List<SyncFile>> set : sets.descendingMap().entrySet()) {
            List<SyncFile> stored = set.getValue().stream().filter(f -> f.status() == FileStatus.STORED).toList();
            if (!stored.isEmpty() && set.getValue().stream().noneMatch(f -> NOT_YET.contains(f.status()))) {
                return Optional.of(new BackfileSet(set.getKey(), stored));
            }
        }
        return Optional.empty();
    }

    private LocalDate dailyFrom() {
        if (properties.dailyFrom().isBlank()) {
            throw new IllegalArgumentException("Without a backfile (require-backfile=false) set "
                    + "listing-guard.trademark.uspto.sync.daily-from to the first day to list.");
        }
        return LocalDate.parse(properties.dailyFrom().trim());
    }

    private Inputs inputs(RunState state, boolean warn) {
        Optional<BackfileSet> backfile = activeBackfile();
        LocalDate through;
        List<SyncFile> set;
        if (backfile.isPresent()) {
            through = backfile.get().through();
            set = backfile.get().files();
            long incomplete = store.files(Kind.BACKFILE).stream()
                    .filter(f -> f.dataDate().isAfter(backfile.get().through()) && NOT_YET.contains(f.status())).count();
            if (incomplete > 0 && warn) {
                state.warn("A newer backfile set is not complete yet (" + incomplete + " file(s) missing); the set up to "
                        + through + " is used.");
            }
        } else if (properties.requireBackfile()) {
            return new Inputs(null, List.of(), List.of(), "No complete backfile set is stored. Load one first: uspto-sync "
                    + "--backfile --input-dir=<folder inside catalogs/raw> (or --backfile with the download on). A daily "
                    + "file holds only the changes of one day.");
        } else {
            through = dailyFrom().minusDays(1);
            set = List.of();
        }
        LocalDate after = through;
        List<SyncFile> dailies = store.files(Kind.DAILY).stream().filter(f -> f.dataDate().isAfter(after)).toList();
        List<SyncFile> missing = dailies.stream().filter(f -> NOT_YET.contains(f.status())).toList();
        if (!missing.isEmpty()) {
            List<String> listed = missing.stream().limit(LISTED_NAMES).map(f -> f.name() + " (" + f.status()
                    + (f.error() == null ? "" : ": " + f.error()) + ")").toList();
            return new Inputs(through, set, List.of(), missing.size() + " daily file(s) after " + through
                    + " are not stored: " + String.join("; ", listed) + (missing.size() > LISTED_NAMES ? "; ..." : "")
                    + ". Run again (the download tries them again), give them with --input-dir, or leave one out on "
                    + "purpose with --ignore-file=<name> --reason=<text>. Nothing was converted.");
        }
        List<SyncFile> stored = dailies.stream().filter(f -> f.status() == FileStatus.STORED).toList();
        long ignored = dailies.stream().filter(f -> f.status() == FileStatus.IGNORED).count();
        if (warn && ignored > 0) {
            state.warn(ignored + " daily file(s) after " + through + " are left out on purpose (--ignore-file).");
        }
        if (warn) {
            warnGaps(through, stored, state);
        }
        if (set.isEmpty() && stored.isEmpty()) {
            return new Inputs(through, set, stored, "There is no stored file to convert.");
        }
        return new Inputs(through, set, stored, null);
    }

    private void warnGaps(LocalDate through, List<SyncFile> dailies, RunState state) {
        LocalDate previous = through;
        for (SyncFile daily : dailies) {
            if (previous != null && previous != LocalDate.MIN
                    && ChronoUnit.DAYS.between(previous, daily.dataDate()) > properties.dailyGapWarningDays()) {
                state.warn("No daily file between " + previous + " and " + daily.dataDate() + " (more than "
                        + properties.dailyGapWarningDays() + " days): check that none is missing.");
            }
            previous = daily.dataDate();
        }
    }

    private void verifyStored(List<SyncFile> files) throws IOException {
        for (SyncFile file : files) {
            Path path = filesDir(file.kind()).resolve(file.name());
            if (!Files.isRegularFile(path) || Files.size(path) != file.sizeBytes()) {
                throw new IllegalStateException("The stored file " + syncDir().relativize(path) + " is missing or its size "
                        + "changed since it was stored; restore it, or give it again with --input-dir after removing it "
                        + "from the store. Nothing was converted.");
            }
            if (properties.verifyStoredSha256() && !sha256(path).equals(file.sha256())) {
                throw new IllegalStateException("The SHA-256 of the stored file " + syncDir().relativize(path)
                        + " changed since it was stored. Nothing was converted.");
            }
        }
    }

    private String digest(Inputs inputs, UsptoStatusMap statusMap, UsptoConvertFilters filters,
                          NormalizationIdentity identity) {
        StringBuilder text = new StringBuilder("listing-guard/uspto-sync-input/v1\n");
        inputs.all().stream().sorted(Comparator.comparing((SyncFile f) -> f.kind().name()).thenComparing(SyncFile::name))
                .forEach(f -> text.append(f.kind()).append(' ').append(f.name()).append(' ').append(f.sha256()).append('\n'));
        text.append("status_map=").append(statusMap.version()).append(' ').append(statusMap.contentHash()).append('\n');
        text.append("filters=").append(filters.version()).append(' ').append(filters.contentHash()).append('\n');
        text.append("normalization=").append(identity.packId()).append(' ').append(identity.normalizationVersion())
                .append(' ').append(identity.normalizationHash()).append('\n');
        text.append("allow_other_version=").append(convert.allowOtherVersion()).append('\n');
        UsptoSyncProperties.Catalog catalog = properties.catalog();
        text.append("catalog=").append(catalog.labelPrefix()).append('\n').append(catalog.sourceName()).append('\n')
                .append(catalog.url()).append('\n').append(catalog.license()).append('\n');
        return HexFormat.of().formatHex(newDigest().digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // --- conversion and import ---

    private record Converted(long versionId, String version, ConversionReport report) {
    }

    private Converted convertAndImport(Inputs inputs, String digest, UsptoStatusMap statusMap, UsptoConvertFilters filters,
                                       NormalizationIdentity identity, RunState state) throws IOException {
        requireFreeDisk(syncDir(), 0, "converting");
        LocalDate dataDate = inputs.dataDate();
        String label = properties.catalog().labelPrefix() + "-" + dataDate.format(DAY) + "-" + digest.substring(0, 8);
        Path catalogDir = catalogDir(label);
        Path raw = catalogDir.resolve("raw");
        Files.createDirectories(raw);
        String file = label + ".jsonl";

        List<UsptoCatalogConverter.Source> sources = new ArrayList<>();
        for (SyncFile input : inputs.all()) {
            Path path = filesDir(input.kind()).resolve(input.name());
            String name = (input.kind() == Kind.DAILY ? "daily/" : "backfile/") + input.name();
            if (UsptoArchive.isArchive(input.name())) {
                sources.addAll(UsptoArchive.sources(path, name, limits()));
            } else {
                sources.add(UsptoCatalogConverter.Source.file(name, path));
            }
        }
        if (sources.size() > UsptoCatalogConverter.MAX_FILES) {
            throw new IllegalStateException(sources.size() + " XML files would be converted in one run, more than "
                    + UsptoCatalogConverter.MAX_FILES + "; load a newer backfile (--backfile) so fewer daily files follow it.");
        }
        TextNormalizer normalizer = new TextNormalizer(rulesets.ruleset().normalization());
        UsptoCatalogConverter converter = new UsptoCatalogConverter(statusMap, filters, normalizer,
                new UsptoCatalogConverter.Options(convert.allowOtherVersion(), true, convert.maxDistinctSerials()));
        state.note("Converting " + sources.size() + " XML file(s) (" + inputs.backfile().size() + " backfile, "
                + inputs.dailies().size() + " daily file(s) after " + inputs.through() + "), strict. The converter is "
                + "unverified against real data (ADR-009).");
        Path part = raw.resolve(file + ".part");
        ConversionReport report;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), BUFFER_BYTES)) {
            report = converter.convert(sources, out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        move(part, raw.resolve(file));
        Files.writeString(raw.resolve(file + ".report.json"), report.toJson(), StandardCharsets.UTF_8);
        if (report.completeness() != Completeness.COMPLETE) {
            throw new IllegalStateException("The conversion is INCOMPLETE; see " + syncDir().relativize(raw.resolve(file
                    + ".report.json")) + ". Nothing was imported.");
        }
        state.note("Converted: " + report.recordsRead() + " records read, " + report.recordsWritten() + " written, "
                + report.superseded() + " replaced by a later transaction, " + report.deadRemoved() + " no longer live, "
                + report.filteredTotal() + " filtered.");

        Files.writeString(catalogDir.resolve(CatalogManifest.FILE_NAME), manifest(label, file, report.outputSha256(),
                dataDate, statusMap, filters), StandardCharsets.UTF_8);
        CatalogManifest.Entry entry = CatalogManifest.load(catalogDir).entry(label);
        TrademarkCatalogImporter.Result imported = importer.importCatalog(entry, raw, normalizer, identity);
        state.note("Imported catalog version " + imported.catalog().version() + " (" + imported.outcome() + ", "
                + imported.counts().loaded() + " entries).");
        return new Converted(imported.catalogVersionId(), imported.catalog().version(), report);
    }

    /** The manifest of one catalog the sync made: the {@code sources:} form with the key {@code uspto}. */
    private String manifest(String label, String file, String sha256, LocalDate sourceDate, UsptoStatusMap statusMap,
                            UsptoConvertFilters filters) throws IOException {
        Set<MarkStatus> statuses = EnumSet.noneOf(MarkStatus.class);
        for (String code : filters.liveStatusCodes()) {
            if (statusMap.outcome(code) instanceof UsptoStatusMap.Mapped mapped) {
                statuses.add(mapped.status());
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_key", UsptoSyncProperties.Catalog.SOURCE_KEY);
        source.put("file", file);
        source.put("source_name", properties.catalog().sourceName());
        source.put("url", properties.catalog().url());
        source.put("license", properties.catalog().license());
        source.put("source_date", sourceDate.toString());
        source.put("sha256", sha256);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", label);
        entry.put("format_version", "jsonl-1");
        entry.put("sources", List.of(source));
        entry.put("filters", Map.of("include_classes", List.copyOf(filters.niceClasses()),
                "include_status", statuses.stream().map(Enum::name).toList()));
        return "# Written by the uspto-sync job (ADR-009, PR A2d); the next runs never change it. The trademark-import job can\n"
                + "# import it again with CATALOGS_DIR set to this folder.\n"
                + YAML.writeValueAsString(Map.of("catalogs", List.of(entry)));
    }

    // --- activation ---

    private Outcome activate(Outcome imported, RunState state) {
        long versionId = imported.catalogVersionId();
        String version = imported.catalogVersion();
        CatalogVersion made = activations.find(versionId).orElseThrow(() -> new IllegalStateException(
                "Catalog version " + version + " disappeared during the run."));
        Optional<Activation> current = activations.current();
        if (current.isPresent() && Objects.equals(current.get().catalogVersionId(), versionId)) {
            return imported.with(RunStatus.NO_CHANGE, "Catalog version " + version + " is already the served version.",
                    state.attention != null);
        }
        if (state.options.noActivate()) {
            return imported.with(RunStatus.IMPORTED, "Catalog version " + version + " is imported and not activated "
                    + "(--no-activate). Review it, then: admin catalog activate " + version, state.attention != null);
        }
        boolean force = state.options.forceActivate();
        if (!force && current.isPresent() && current.get().reason() == CatalogActivations.Reason.ROLLBACK) {
            state.warn("The served version " + current.get().catalogVersion() + " was chosen by a rollback, so the sync "
                    + "does not activate " + version + " by itself.");
            return imported.with(RunStatus.IMPORTED, "Catalog version " + version + " is imported and not activated: the "
                    + "served version was chosen by a rollback. End the rollback with: admin catalog activate <version>",
                    state.attention != null);
        }
        if (!force && current.isPresent() && current.get().catalogVersionId() != null) {
            Optional<CatalogVersion> served = activations.find(current.get().catalogVersionId());
            if (served.isPresent() && made.entries() < properties.minLoadedRatio() * served.get().entries()) {
                return imported.with(RunStatus.IMPORTED, "Catalog version " + version + " has " + made.entries()
                        + " entries, the served " + served.get().version() + " has " + served.get().entries()
                        + ": fewer than " + properties.minLoadedRatio() + " of them, so it is not activated (a lost file?). "
                        + "Read its report, then activate it with `admin catalog activate " + version + "` or run again "
                        + "with --force-activate.", true);
            }
        }
        Activation activation = activations.activate(versionId, CatalogActivations.Reason.SYNC, state.id,
                "uspto-sync run " + state.id);
        return imported.with(RunStatus.ACTIVATED, "Catalog version " + version + " is activated (activation "
                + activation.id() + "). An API that follows the activation log serves it within its refresh interval.",
                state.attention != null);
    }

    // --- retention ---

    /** @param thisRun the version this run made or reused: the newest, although the run row does not name it yet */
    private void prune(long thisRun, RunState state) throws IOException {
        List<Long> made = new ArrayList<>(List.of(thisRun));
        store.catalogVersionIds().stream().filter(id -> id != thisRun).forEach(made::add);
        Set<Long> keep = new HashSet<>(made.subList(0, Math.min(properties.keepVersions(), made.size())));
        activations.current().map(Activation::catalogVersionId).ifPresent(keep::add);
        keep.addAll(jdbc.queryForList("""
                SELECT DISTINCT a.catalog_version_id FROM trademark_catalog_activation a
                WHERE a.catalog_version_id IS NOT NULL
                  AND COALESCE((SELECT min(n.activated_at) FROM trademark_catalog_activation n WHERE n.id > a.id), now())
                      > now() - make_interval(secs => ?)""", Long.class, (double) properties.pruneGrace().toSeconds()));
        for (Long id : made) {
            if (keep.contains(id)) {
                continue;
            }
            Optional<CatalogVersion> version = activations.find(id);
            if (version.isEmpty()) {
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> {
                    jdbc.update("DELETE FROM trademark_entry WHERE catalog_version_id = ?", id);
                    jdbc.update("DELETE FROM trademark_catalog_version WHERE id = ?", id);
                });
            } catch (DataIntegrityViolationException e) {
                state.note("Catalog version " + version.get().version() + " is kept: retained trademark results still name "
                        + "it; a later run deletes it.");
                continue;
            }
            deleteTree(catalogDir(version.get().label()));
            state.note("Deleted catalog version " + version.get().version() + " (it is older than the "
                    + properties.keepVersions() + " newest the sync keeps).");
        }
    }

    private void reportSuperseded(LocalDate through, RunState state) throws IOException {
        if (through == null) {
            return;
        }
        long count = 0;
        long bytes = 0;
        for (Kind kind : Kind.values()) {
            for (SyncFile file : store.files(kind)) {
                boolean old = kind == Kind.BACKFILE ? file.dataDate().isBefore(through) : !file.dataDate().isAfter(through);
                if (old && file.status() == FileStatus.STORED && Files.exists(filesDir(kind).resolve(file.name()))) {
                    count++;
                    bytes += file.sizeBytes();
                }
            }
        }
        if (count > 0) {
            state.note(count + " stored file(s), " + bytes + " bytes, are no longer read (backfile sets before " + through
                    + " and daily files up to it). Delete them from " + syncDir().resolve("files")
                    + " when you no longer need them.");
        }
    }

    // --- dry run ---

    private Result dryRun(Mode mode, Options options) throws IOException {
        RunState state = new RunState(-1, mode, options);
        switch (mode) {
            case OFFLINE -> {
                Kind kind = options.backfile() ? Kind.BACKFILE : Kind.DAILY;
                Path input = inputFolder(options.inputDir());
                for (Candidate candidate : candidates(input, options, state.notes)) {
                    Optional<SyncFile> known = store.file(kind, candidate.name());
                    String what = known.isPresent() && known.get().status() == FileStatus.STORED ? "stored already"
                            : "would be registered (" + candidate.date() + ")";
                    state.note(candidate.name() + ": " + what);
                }
            }
            case DOWNLOAD -> download(state, false);
            case STORE -> {
                Inputs inputs = inputs(state, false);
                if (inputs.blocked() != null) {
                    state.note("Blocked: " + inputs.blocked());
                } else {
                    String digest = digest(inputs, statusMap(), filters(), NormalizationIdentity.of(rulesets.ruleset()));
                    state.note(store.runWithDigest(digest).map(r -> "These inputs made catalog version "
                            + r.catalogVersion() + " (run " + r.id() + "); nothing would be converted.")
                            .orElse(inputs.all().size() + " stored file(s) would be converted."));
                }
            }
        }
        return new Result(null, null, null, "Dry run: nothing was downloaded, copied, imported or activated.",
                state.notes, state.stopped != null || state.attention != null);
    }

    // --- helpers ---

    private Path catalogsDir() {
        return Path.of(trademark.catalogsDir()).toAbsolutePath().normalize();
    }

    private Path rawDir() {
        return catalogsDir().resolve("raw");
    }

    private Path syncDir() {
        Path catalogs = catalogsDir();
        Path dir = catalogs.resolve(properties.dir()).normalize();
        if (!dir.startsWith(catalogs) || dir.equals(catalogs)) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.dir must be a folder inside " + catalogs
                    + ", got '" + properties.dir() + "'.");
        }
        return dir;
    }

    private Path filesDir(Kind kind) {
        return syncDir().resolve("files").resolve(kind == Kind.DAILY ? "daily" : "backfile");
    }

    private Path catalogDir(String label) {
        return syncDir().resolve("catalog").resolve(label);
    }

    private UsptoArchive.Limits limits() {
        UsptoSyncProperties.Archive archive = properties.archive();
        return new UsptoArchive.Limits(archive.maxEntries(), archive.maxEntrySize().toBytes(), archive.maxRatio());
    }

    private UsptoStatusMap statusMap() throws IOException {
        return UsptoStatusMap.parse(resources.getResource(convert.statusMap()).getContentAsString(StandardCharsets.UTF_8));
    }

    private UsptoConvertFilters filters() throws IOException {
        return UsptoConvertFilters.parse(resources.getResource(convert.filters()).getContentAsString(StandardCharsets.UTF_8));
    }

    private void requireFreeDisk(Path dir, long needed, String what) throws IOException {
        Files.createDirectories(dir);
        long free = Files.getFileStore(dir).getUsableSpace();
        long wanted = properties.minFreeDisk().toBytes() + Math.max(needed, 0);
        if (free < wanted) {
            throw new IllegalStateException("Not enough free disk for " + what + ": " + free + " bytes free, "
                    + wanted + " wanted (listing-guard.trademark.uspto.sync.min-free-disk plus the file). Nothing more "
                    + "was done.");
        }
    }

    private void deleteTree(Path dir) throws IOException {
        Path catalogs = syncDir().resolve("catalog");
        if (!dir.normalize().startsWith(catalogs) || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String printable(String name) {
        StringBuilder out = new StringBuilder("'");
        name.codePoints().limit(100).forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? '?' : c));
        return out.append('\'').toString();
    }
}
