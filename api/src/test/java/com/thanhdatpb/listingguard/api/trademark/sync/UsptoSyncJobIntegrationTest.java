package com.thanhdatpb.listingguard.api.trademark.sync;

import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.cancelled;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.live;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.write;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.xml;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Activation;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogLock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.UsptoConvertProperties;
import com.thanhdatpb.listingguard.api.trademark.sync.FakeOdpServer.Behaviour;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncJob.Options;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncJob.Result;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.FileStatus;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Kind;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Origin;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.RunStatus;
import com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.SyncFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code uspto-sync} job end to end against a real PostgreSQL (ADR-009, PR A2d): offline folders and a backfile, the
 * download from a stand-in of the ODP API on the loopback address (no real network), the state that makes a run after a
 * failure fetch only what is missing, the activation gates, the rollback hold and the retention. Every mark, serial number
 * and file is synthetic.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class UsptoSyncJobIntegrationTest {

    private static final String LICENSE = "Synthetic test files; no third-party terms";
    private static final String KEY = "odp-sync-test-key-0123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T11:00:00Z"), ZoneOffset.UTC);
    private static final String BACKFILE_1 = "apc18840407-20251231-01.zip";
    private static final String BACKFILE_2 = "apc18840407-20251231-02.zip";

    @Autowired
    UsptoConvertProperties convert;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    ResourceLoader resources;

    @Autowired
    UsptoSyncStore store;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    CatalogActivations activations;

    @Autowired
    TrademarkCatalogLock lock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @TempDir
    Path catalogs;

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE uspto_sync_file, uspto_sync_run, trademark_catalog_activation");
    }

    // --- set-up ---

    private UsptoSyncProperties properties(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(Map.of(
                "listing-guard.trademark.uspto.sync.keep-versions", "2",
                "listing-guard.trademark.uspto.sync.prune-grace", "0s",
                "listing-guard.trademark.uspto.sync.min-free-disk", "1MB",
                "listing-guard.trademark.uspto.sync.catalog.license", LICENSE));
        values.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("listing-guard.trademark.uspto.sync", UsptoSyncProperties.class);
    }

    private UsptoSyncProperties downloading(FakeOdpServer server) {
        return properties(Map.of(
                "listing-guard.trademark.uspto.sync.download.enabled", "true",
                "listing-guard.trademark.uspto.sync.download.api-key", KEY,
                "listing-guard.trademark.uspto.sync.download.base-url", server.baseUrl(),
                "listing-guard.trademark.uspto.sync.download.allowed-hosts", "127.0.0.1",
                "listing-guard.trademark.uspto.sync.download.min-interval", "0s",
                "listing-guard.trademark.uspto.sync.download.initial-backoff", "1ms",
                "listing-guard.trademark.uspto.sync.download.max-backoff", "2s",
                "listing-guard.trademark.uspto.sync.download.max-attempts", "3"));
    }

    private UsptoSyncJob job(UsptoSyncProperties properties) {
        UsptoBulkClient client = properties.download().enabled()
                ? new UsptoBulkClient(properties.download(), CLOCK, duration -> { }) : null;
        return new UsptoSyncJob(properties, convert, new TrademarkProperties(
                "classpath:rulesets/trademark-risk-2026-11b.yaml", List.of("ETSY", "AMAZON"), "", catalogs.toString()),
                rulesets, resources, store, importer, activations, lock, jdbc, transactions, client, CLOCK);
    }

    private UsptoSyncJob offline() {
        return job(properties(Map.of()));
    }

    private static Options inputDir(String folder) {
        return new Options(folder, false, null, false, false, false, List.of(), null, null);
    }

    private static Options backfile(String folder) {
        return new Options(folder, true, null, false, false, false, List.of(), null, null);
    }

    private Path inbox(String folder) {
        return catalogs.resolve("raw").resolve("inbox").resolve(folder);
    }

    private void backfileFiles(String folder, String part1, String part2) {
        write(inbox(folder), part1, zip(part1, xml(
                live("90000001", "20251201", "Zorvex"),
                live("90000002", "20251202", "Brasktone"))));
        write(inbox(folder), part2, zip(part2, xml(
                live("90000003", "20251203", "Quillfeather"),
                cancelled("90000004", "20251204", "Marlowick"))));
    }

    private void day(String folder, String name, String... caseFiles) {
        write(inbox(folder), name, zip(name, xml(caseFiles)));
    }

    private Result loadBackfile() throws IOException {
        backfileFiles("backfile", BACKFILE_1, BACKFILE_2);
        return offline().run(backfile("inbox/backfile"));
    }

    private List<String> marks(String version) {
        return jdbc.queryForList("""
                SELECT e.display FROM trademark_entry e JOIN trademark_catalog_version v ON v.id = e.catalog_version_id
                WHERE v.version = ? ORDER BY e.display""", String.class, version);
    }

    private Path syncDir() {
        return catalogs.resolve("raw").resolve("uspto-sync");
    }

    private String label(String version) {
        return jdbc.queryForObject("SELECT label FROM trademark_catalog_version WHERE version = ?", String.class, version);
    }

    // --- offline ---

    @Test
    void anOfflineBackfileThenDailyFilesKeepTheCatalogCurrentAndOldVersionsGo() throws IOException {
        Result first = loadBackfile();

        assertThat(first.status()).isEqualTo(RunStatus.ACTIVATED);
        assertThat(first.needsAttention()).isFalse();
        assertThat(marks(first.catalogVersion())).containsExactly("Brasktone", "Quillfeather", "Zorvex");
        List<SyncFile> backfile = store.files(Kind.BACKFILE);
        assertThat(backfile).extracting(SyncFile::name).containsExactly(BACKFILE_1, BACKFILE_2);
        assertThat(backfile).allSatisfy(file -> {
            assertThat(file.status()).isEqualTo(FileStatus.STORED);
            assertThat(file.origin()).isEqualTo(Origin.OFFLINE);
            assertThat(file.dataDate()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(file.sha256()).hasSize(64);
            assertThat(file.storedAt()).isNotNull();
            assertThat(Files.size(syncDir().resolve("files/backfile").resolve(file.name()))).isEqualTo(file.sizeBytes());
        });
        String firstLabel = label(first.catalogVersion());
        Path catalogDir = syncDir().resolve("catalog").resolve(firstLabel);
        assertThat(catalogDir.resolve("manifest.yaml")).content(StandardCharsets.UTF_8).contains("source_key: \"uspto\"")
                .contains(LICENSE);
        assertThat(catalogDir.resolve("raw").resolve(firstLabel + ".jsonl.report.json")).exists();
        Activation activation = activations.current().orElseThrow();
        assertThat(activation.catalogVersion()).isEqualTo(first.catalogVersion());
        assertThat(activation.reason()).isEqualTo(CatalogActivations.Reason.SYNC);
        assertThat(activation.syncRunId()).isEqualTo(first.runId());
        assertThat(jdbc.queryForObject("""
                SELECT e.source_ids[1] FROM trademark_entry e JOIN trademark_catalog_version v ON v.id = e.catalog_version_id
                WHERE v.version = ? AND e.display = 'Zorvex'""", String.class, first.catalogVersion()))
                .as("source ids carry the uspto key, so the TSDR links apply").isEqualTo("uspto:90000001");

        Result again = offline().run(Options.defaults());
        assertThat(again.status()).isEqualTo(RunStatus.NO_CHANGE);
        assertThat(again.notes()).anyMatch(note -> note.contains("is reused, nothing is converted"));

        day("day1", "apc260105.zip", cancelled("90000002", "20260105", "Brasktone"), live("90000005", "20260105", "Flintwave"));
        Result second = offline().run(inputDir("inbox/day1"));
        assertThat(second.status()).isEqualTo(RunStatus.ACTIVATED);
        assertThat(marks(second.catalogVersion())).as("Brasktone was cancelled in the later file")
                .containsExactly("Flintwave", "Quillfeather", "Zorvex");

        day("day2", "apc260106.zip", live("90000006", "20260106", "Corvanta"));
        Result third = offline().run(inputDir("inbox/day2"));
        day("day3", "apc260107.zip", live("90000007", "20260107", "Sylvarine"));
        Result fourth = offline().run(inputDir("inbox/day3"));

        assertThat(fourth.status()).isEqualTo(RunStatus.ACTIVATED);
        assertThat(marks(fourth.catalogVersion())).containsExactly("Corvanta", "Flintwave", "Quillfeather", "Sylvarine",
                "Zorvex");
        assertThat(activations.find(fourth.catalogVersion())).isPresent();
        assertThat(activations.find(third.catalogVersion())).as("keep-versions 2").isPresent();
        assertThat(activations.find(second.catalogVersion())).isEmpty();
        assertThat(activations.find(first.catalogVersion())).isEmpty();
        assertThat(catalogDir).as("the files of a deleted version go too").doesNotExist();
        assertThat(third.notes()).anyMatch(note -> note.contains("Deleted catalog version " + first.catalogVersion()));
        assertThat(fourth.notes()).anyMatch(note -> note.contains("Deleted catalog version " + second.catalogVersion()));
        assertThat(activations.history(10).get(1).catalogVersion()).isEqualTo(third.catalogVersion());
        assertThat(jdbc.queryForList("SELECT status FROM uspto_sync_run ORDER BY id", String.class))
                .containsExactly("ACTIVATED", "NO_CHANGE", "ACTIVATED", "ACTIVATED", "ACTIVATED");
    }

    @Test
    void dailyFilesWithoutABackfileAreBlocked() throws IOException {
        day("day1", "apc260105.zip", live("90000005", "20260105", "Flintwave"));

        Result result = offline().run(inputDir("inbox/day1"));

        assertThat(result.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(result.needsAttention()).isTrue();
        assertThat(result.message()).contains("No complete backfile set");
        assertThat(activations.current()).isEmpty();
    }

    @Test
    void aMaliciousArchiveRegistersNothing() throws IOException {
        write(inbox("evil"), BACKFILE_1, zip(BACKFILE_1, xml(live("90000001", "20251201", "Zorvex"))));
        write(inbox("evil"), BACKFILE_2, zip("../../escape.xml",
                xml(live("90000002", "20251202", "Brasktone")).getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> offline().run(backfile("inbox/evil"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing was registered").hasMessageContaining("'..' segment");

        assertThat(store.files(Kind.BACKFILE)).isEmpty();
        assertThat(syncDir().resolve("files/backfile")).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT status FROM uspto_sync_run", String.class)).isEqualTo("FAILED");
        assertThat(catalogs.resolve("escape.xml")).doesNotExist();
    }

    @Test
    void aStoredFileNeverChangesAndABackfileNeedsAllItsParts() throws IOException {
        loadBackfile();
        day("day1", "apc260105.zip", live("90000005", "20260105", "Flintwave"));
        offline().run(inputDir("inbox/day1"));
        day("other", "apc260105.zip", live("90000099", "20260105", "Tessaline"));

        assertThatThrownBy(() -> offline().run(inputDir("inbox/other"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another SHA-256");

        backfileFiles("gap", "apc18840407-20260630-01.zip", "apc18840407-20260630-03.zip");
        assertThatThrownBy(() -> offline().run(backfile("inbox/gap"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("misses the part(s) [2]");
        assertThat(store.files(Kind.BACKFILE)).extracting(SyncFile::name).containsExactly(BACKFILE_1, BACKFILE_2);
    }

    @Test
    void theTermsMustBeStatedAndOneRunAtATime() throws IOException {
        backfileFiles("backfile", BACKFILE_1, BACKFILE_2);
        UsptoSyncJob withoutTerms = job(properties(Map.of("listing-guard.trademark.uspto.sync.catalog.license", "")));
        assertThatThrownBy(() -> withoutTerms.run(backfile("inbox/backfile"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USPTO_SYNC_LICENSE");

        try (TrademarkCatalogLock.Held held = lock.tryAcquire().orElseThrow()) {
            assertThatThrownBy(() -> offline().run(backfile("inbox/backfile"))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("holds the catalog lock");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uspto_sync_run", Integer.class)).isZero();
        assertThat(store.files(Kind.BACKFILE)).isEmpty();
    }

    @Test
    void aDryRunChangesNothing() throws IOException {
        backfileFiles("backfile", BACKFILE_1, BACKFILE_2);

        Result result = offline().run(new Options("inbox/backfile", true, null, false, false, true, List.of(), null, null));

        assertThat(result.runId()).isNull();
        assertThat(result.notes()).anyMatch(note -> note.startsWith(BACKFILE_1 + ": would be registered (2025-12-31)"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uspto_sync_run", Integer.class)).isZero();
        assertThat(store.files(Kind.BACKFILE)).isEmpty();
        assertThat(syncDir()).doesNotExist();
    }

    // --- activation gates ---

    @Test
    void aRollbackHoldsTheNextActivationUntilAnOperatorActivates() throws IOException {
        Result first = loadBackfile();
        day("day1", "apc260105.zip", live("90000005", "20260105", "Flintwave"));
        offline().run(inputDir("inbox/day1"));
        activations.rollback(null, "test");

        day("day2", "apc260106.zip", live("90000006", "20260106", "Corvanta"));
        Result held = offline().run(inputDir("inbox/day2"));

        assertThat(held.status()).isEqualTo(RunStatus.IMPORTED);
        assertThat(held.needsAttention()).isFalse();
        assertThat(held.message()).contains("rollback");
        assertThat(activations.current().orElseThrow().catalogVersion()).isEqualTo(first.catalogVersion());

        activations.activate(held.catalogVersion(), CatalogActivations.Reason.MANUAL, null, "reviewed");
        day("day3", "apc260107.zip", live("90000007", "20260107", "Sylvarine"));
        Result resumed = offline().run(inputDir("inbox/day3"));
        assertThat(resumed.status()).isEqualTo(RunStatus.ACTIVATED);
    }

    @Test
    void aMuchSmallerCatalogIsImportedButNotActivatedUnlessForced() throws IOException {
        Result first = loadBackfile();
        write(inbox("small"), "apc18840407-20260630-01.zip", zip("apc18840407-20260630-01.zip",
                xml(live("90000001", "20260601", "Zorvex"))));

        Result small = offline().run(backfile("inbox/small"));

        assertThat(small.status()).isEqualTo(RunStatus.IMPORTED);
        assertThat(small.needsAttention()).isTrue();
        assertThat(small.message()).contains("has 1 entries").contains("fewer than 0.9");
        assertThat(activations.current().orElseThrow().catalogVersion()).isEqualTo(first.catalogVersion());

        Result forced = offline().run(new Options(null, false, null, false, true, false, List.of(), null, null));
        assertThat(forced.status()).isEqualTo(RunStatus.ACTIVATED);
        assertThat(forced.catalogVersion()).isEqualTo(small.catalogVersion());
        assertThat(forced.notes()).anyMatch(note -> note.contains("is reused"));
    }

    @Test
    void importWithoutActivating() throws IOException {
        backfileFiles("backfile", BACKFILE_1, BACKFILE_2);

        Result result = offline().run(new Options("inbox/backfile", true, null, true, false, false, List.of(), null, null));

        assertThat(result.status()).isEqualTo(RunStatus.IMPORTED);
        assertThat(result.message()).contains("admin catalog activate " + result.catalogVersion());
        assertThat(activations.current()).isEmpty();
    }

    // --- download ---

    @Test
    void aRunAfterAFailureFetchesOnlyWhatIsMissing() throws IOException {
        loadBackfile();
        try (FakeOdpServer server = new FakeOdpServer()) {
            server.offer("TRTDXFAP", "apc260105.zip", zip("apc260105.zip", xml(live("90000005", "20260105", "Flintwave"))))
                    .offer("TRTDXFAP", "apc260106.zip", zip("apc260106.zip", xml(live("90000006", "20260106", "Corvanta"))))
                    .behave("apc260106.zip", Behaviour.FAIL);
            UsptoSyncJob job = job(downloading(server));

            Result blocked = job.run(Options.defaults());

            assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);
            assertThat(blocked.needsAttention()).isTrue();
            assertThat(blocked.message()).contains("apc260106.zip").contains("HTTP 500");
            assertThat(store.file(Kind.DAILY, "apc260105.zip").orElseThrow().status()).isEqualTo(FileStatus.STORED);
            SyncFile failed = store.file(Kind.DAILY, "apc260106.zip").orElseThrow();
            assertThat(failed.status()).isEqualTo(FileStatus.FAILED);
            assertThat(failed.attempts()).isEqualTo(3);
            assertThat(failed.error()).doesNotContain(KEY);
            assertThat(server.downloads("apc260105.zip")).isEqualTo(1);
            assertThat(server.downloads("apc260106.zip")).isEqualTo(3);
            assertThat(server.requests()).allSatisfy(request -> assertThat(request.key()).isEqualTo(KEY));
            assertThat(server.requests().get(0).query()).contains("fileDataFromDate=2026-01-01");

            server.behave("apc260106.zip", Behaviour.RATE_LIMITED_ONCE);
            Result resumed = job.run(Options.defaults());

            assertThat(resumed.status()).isEqualTo(RunStatus.ACTIVATED);
            assertThat(server.downloads("apc260105.zip")).as("a stored file is never fetched again").isEqualTo(1);
            assertThat(server.downloads("apc260106.zip")).as("429 once, then the file").isEqualTo(5);
            assertThat(marks(resumed.catalogVersion())).contains("Corvanta", "Flintwave");
            SyncFile stored = store.file(Kind.DAILY, "apc260106.zip").orElseThrow();
            assertThat(stored.origin()).isEqualTo(Origin.DOWNLOAD);
            assertThat(stored.attempts()).isEqualTo(5);
        }
    }

    @Test
    void aMissingOrDamagedFileBlocksTheRunUntilItIsLeftOutOnPurpose() throws IOException {
        Result first = loadBackfile();
        try (FakeOdpServer server = new FakeOdpServer()) {
            server.offer("TRTDXFAP", "apc260105.zip", new byte[] {1}).behave("apc260105.zip", Behaviour.MISSING)
                    .offer("TRTDXFAP", "apc260106.zip", "<html>an error page, not a zip</html>"
                            .getBytes(StandardCharsets.UTF_8));
            UsptoSyncJob job = job(downloading(server));

            Result blocked = job.run(Options.defaults());

            assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);
            assertThat(store.file(Kind.DAILY, "apc260105.zip").orElseThrow().error()).contains("HTTP 404");
            assertThat(store.file(Kind.DAILY, "apc260106.zip").orElseThrow().error()).contains("not a readable zip");
            assertThat(server.downloads("apc260105.zip")).as("404 is not tried again").isEqualTo(1);

            Result ignored = job.run(new Options(null, false, null, false, false, false,
                    List.of("apc260105.zip", "apc260106.zip"), "broken at the source", null));

            assertThat(ignored.status()).as("only the backfile is left, which made the served version")
                    .isEqualTo(RunStatus.NO_CHANGE);
            assertThat(ignored.catalogVersion()).isEqualTo(first.catalogVersion());
            assertThat(ignored.notes()).anyMatch(note -> note.contains("left out on purpose"));
            assertThat(store.file(Kind.DAILY, "apc260105.zip").orElseThrow().status()).isEqualTo(FileStatus.IGNORED);
        }
    }

    @Test
    void aDryRunOfTheDownloadListsWhatIsMissingAndWritesNothing() throws IOException {
        loadBackfile();
        try (FakeOdpServer server = new FakeOdpServer()) {
            server.offer("TRTDXFAP", "apc260105.zip", zip("apc260105.zip", xml(live("90000005", "20260105", "Flintwave"))));

            Result result = job(downloading(server)).run(new Options(null, false, null, false, false, true, List.of(), null,
                    null));

            assertThat(result.runId()).isNull();
            assertThat(result.notes()).anyMatch(note -> note.contains("1 of them are not stored: apc260105.zip"));
            assertThat(server.downloads("apc260105.zip")).isZero();
            assertThat(store.files(Kind.DAILY)).isEmpty();
        }
    }

    @Test
    void theDownloadNeedsTheKey() throws IOException {
        loadBackfile();
        try (FakeOdpServer server = new FakeOdpServer()) {
            Map<String, String> noKey = new HashMap<>(Map.of(
                    "listing-guard.trademark.uspto.sync.download.enabled", "true",
                    "listing-guard.trademark.uspto.sync.download.base-url", server.baseUrl(),
                    "listing-guard.trademark.uspto.sync.download.allowed-hosts", "127.0.0.1"));
            UsptoSyncJob job = job(properties(noKey));

            assertThatThrownBy(() -> job.run(Options.defaults())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LISTING_GUARD_USPTO_ODP_API_KEY is empty");
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void anOfflineRunNeverUsesTheNetworkEvenWithTheDownloadOn() throws IOException {
        try (FakeOdpServer server = new FakeOdpServer()) {
            backfileFiles("backfile", BACKFILE_1, BACKFILE_2);

            Result result = job(downloading(server)).run(backfile("inbox/backfile"));

            assertThat(result.status()).isEqualTo(RunStatus.ACTIVATED);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void storedFilesAreCheckedBeforeEveryConversion() throws IOException {
        loadBackfile();
        Path stored = syncDir().resolve("files/backfile").resolve(BACKFILE_1);
        Files.write(stored, zip(BACKFILE_1, xml(live("90000098", "20251201", "Gravimont"))));
        day("day1", "apc260105.zip", live("90000005", "20260105", "Flintwave"));

        assertThatThrownBy(() -> offline().run(inputDir("inbox/day1"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed since it was stored");
        Optional<Activation> current = activations.current();
        assertThat(current).isPresent();
        try (Stream<Path> files = Files.list(syncDir().resolve("catalog"))) {
            assertThat(files.count()).as("no new catalog was written").isEqualTo(1);
        }
    }
}
