package com.thanhdatpb.listingguard.api.trademark.tsdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckStore;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.tsdr.FakeTsdrServer.Behaviour;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusJob.Options;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusJob.Result;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.CallResult;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Mode;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.RunStatus;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Source;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.StatusClass;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code tsdr-status} job against a real PostgreSQL and a stand-in TSDR on the loopback address (ADR-009, "TSDR status
 * check"): which marks it picks and in what order, live and dead answers, "not found", a limit, a timeout and a wrong key, no
 * second call within the configured interval, the offline files, the report and the optional status on a finding. Every mark,
 * serial number and document is synthetic and nothing talks to the real TSDR.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class TsdrStatusJobIntegrationTest {

    private static final String KEY = "tsdr-job-test-key-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-12T11:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TsdrStatusStore store;

    @Autowired
    TsdrStatusLock lock;

    @Autowired
    TrademarkCheckService checkService;

    @Autowired
    TrademarkCheckStore checkStore;

    @Autowired
    NormalizerRegistry normalizers;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    PlanService plans;

    @TempDir
    Path catalogs;

    private FakeTsdrServer server;
    private final MutableClock clock = new MutableClock(NOW);
    private UsptoStatusMap statusMap;
    private String liveCode;
    private String deadCode;
    private String unsettledCode;
    private long catalogId;
    private UUID clientId;

    /** A clock a test can move. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        server = new FakeTsdrServer();
        jdbc.execute("TRUNCATE tsdr_status, tsdr_status_run");
        statusMap = UsptoStatusMap.parse(new ClassPathResource("uspto/uspto-status-map-2026-11.yaml")
                .getContentAsString(StandardCharsets.UTF_8));
        liveCode = codeOf(UsptoStatusMap.Target.REGISTERED);
        deadCode = codeOf(UsptoStatusMap.Target.CANCELLED);
        unsettledCode = codeOf(UsptoStatusMap.Target.DROP);
        catalogId = jdbc.queryForObject("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license,
                    source_date, file_sha256, format_version, filters_json, pack_id, normalization_version, normalization_hash,
                    records_read, records_filtered, records_merged, records_loaded)
                VALUES (?, 'tsdr-test', 'synthetic', 'ref', 'test', current_date, repeat('a', 64), 'jsonl-1', '{}',
                    'trademark-risk', 'normalization-1', repeat('b', 64), 0, 0, 0, 0) RETURNING id
                """, Long.class, "tsdr-test-" + UUID.randomUUID());
        clientId = jdbc.queryForObject("INSERT INTO client (name) VALUES (?) RETURNING id", UUID.class,
                "tsdr-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String codeOf(UsptoStatusMap.Target target) {
        return statusMap.entries().entrySet().stream().filter(e -> e.getValue().target() == target)
                .map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
    }

    // --- set-up helpers ---

    private void mark(String serial, String display) {
        jdbc.update("""
                INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_ids)
                VALUES (?, ?, ?, ARRAY[25]::smallint[], 'REGISTERED', ARRAY[?]::text[])
                """, catalogId, display.toLowerCase(), display, "uspto:" + serial);
    }

    /** A check made {@code daysAgo} days ago and retained {@code retainedDays}, with one finding for each list of source ids. */
    @SafeVarargs
    private UUID check(int daysAgo, int retainedDays, List<String>... findings) {
        UUID id = UUID.randomUUID();
        Instant created = clock.instant().minus(Duration.ofDays(daysAgo));
        jdbc.update("""
                INSERT INTO trademark_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    catalog_version_id, analysis_version, completeness, risk_level, disclaimer, created_at, expires_at)
                VALUES (?, ?, 'ETSY', 'trademark-risk', ?, (SELECT id FROM ruleset_version WHERE pack_id = 'trademark-risk'
                    AND marketplace_code = 'ETSY' ORDER BY id DESC LIMIT 1), ?, ?, 'COMPLETE', 'HIGH', 'd', ?, ?)
                """, id, clientId, hex(), catalogId, hex(), java.sql.Timestamp.from(created),
                java.sql.Timestamp.from(created.plus(Duration.ofDays(retainedDays))));
        int position = 0;
        for (List<String> sourceIds : findings) {
            String array = "ARRAY[" + String.join(",", sourceIds.stream().map(s -> "'" + s + "'").toList()) + "]::text[]";
            jdbc.update("""
                    INSERT INTO trademark_check_finding (check_id, position, rule_id, risk, field, span_start, span_end, snippet,
                        matched_mark, match_type, inside_color_phrase, message, suggestion, source_ids)
                    VALUES (?, ?, 'catalog_exact', 'HIGH', 'title', 0, 1, 'synthetic', 'mark', 'EXACT', FALSE, 'm', 's',
                    """ + array + ")", id, position++);
        }
        return id;
    }

    /** {@code times} findings in one fresh check that name this serial number. */
    @SuppressWarnings("unchecked")
    private void named(String serial, int times) {
        List<String>[] findings = new List[times];
        for (int i = 0; i < times; i++) {
            findings[i] = List.of("uspto:" + serial);
        }
        check(2, 28, findings);
    }

    private static String hex() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private TsdrProperties properties(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(Map.of("download.enabled", "true", "download.base-url", server.baseUrl(),
                "download.api-key", KEY));
        values.putAll(extra);
        return FakeTsdrServer.properties(values);
    }

    private TsdrStatusJob job(TsdrProperties properties, boolean withClient) {
        TsdrClient client = withClient ? new TsdrClient(properties.download(), clock, duration -> { }) : null;
        Supplier<TsdrStatusJob.ServedCatalog> served = () -> new TsdrStatusJob.ServedCatalog(catalogId, "tsdr-test");
        return new TsdrStatusJob(properties, new TrademarkProperties("classpath:rulesets/trademark-risk-2026-11b.yaml",
                List.of("ETSY"), "", catalogs.toString()), statusMap, store, lock, served, client, clock);
    }

    private Result live(Map<String, String> extra) throws IOException {
        return job(properties(extra), true).run(new Options(null, null, false));
    }

    private List<Map<String, Object>> rows(String where) {
        return jdbc.queryForList("SELECT * FROM tsdr_status " + where + " ORDER BY id");
    }

    private String callResult(String serial) {
        return jdbc.queryForObject("SELECT call_result FROM tsdr_status WHERE serial_number = ? ORDER BY id DESC LIMIT 1",
                String.class, serial);
    }

    private JsonNode report(Result result) throws IOException {
        assertThat(result.reportDir()).isNotNull();
        return JSON.readTree(Files.readString(result.reportDir().resolve("report.json")));
    }

    // --- the tests ---

    @Test
    void picksTheMostNamedMarksFirstAndOnlyThoseOfTheServedCatalogNamedRecently() throws Exception {
        mark("90000001", "Alpha");
        named("90000001", 3);
        mark("90000002", "Bravo");
        named("90000002", 5);
        mark("90000003", "Charlie");
        named("90000003", 1);
        mark("90000004", "Delta"); // in the catalog, named by nobody
        mark("90000005", "Echo");
        check(5, 1, List.of("uspto:90000005")); // its check has expired
        mark("90000006", "Foxtrot");
        check(40, 100, List.of("uspto:90000006")); // still retained, but named before the window
        named("90000099", 4); // named, but not a mark of the served catalog
        check(2, 28, List.of("etsy:90000007", "uspto:abc")); // not a USPTO serial number
        server.serve("90000001", Behaviour.OK, FakeTsdrServer.xml(liveCode, "LIVE", "2020-01-02"))
                .serve("90000002", Behaviour.OK, FakeTsdrServer.xml(deadCode, "ABANDONED", "2024-05-06"))
                .serve("90000003", Behaviour.NOT_FOUND);

        Result result = live(Map.of("max-marks-per-run", "2"));

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(server.requests()).extracting(FakeTsdrServer.Request::path).containsExactly(
                "/ts/cd/casestatus/sn90000002/info.xml", "/ts/cd/casestatus/sn90000001/info.xml");
        assertThat(rows("")).hasSize(2);
        Map<String, Object> dead = rows("WHERE serial_number = '90000002'").get(0);
        assertThat(dead).containsEntry("call_result", "OK").containsEntry("status_code", deadCode)
                .containsEntry("status_class", "DEAD").containsEntry("source", "API")
                .containsEntry("status_map_version", statusMap.version());
        assertThat(dead.get("status_date").toString()).isEqualTo("2024-05-06");
        assertThat(((java.sql.Timestamp) dead.get("checked_at")).toInstant()).isEqualTo(NOW);
        assertThat(rows("WHERE serial_number = '90000001'").get(0)).containsEntry("status_class", "LIVE");
    }

    @Test
    void aSecondRunDoesNotCallAgainWithinTheIntervalsAndTheNextOneTakesTheRest() throws Exception {
        mark("90000001", "Alpha");
        named("90000001", 3);
        mark("90000002", "Bravo");
        named("90000002", 5);
        mark("90000003", "Charlie");
        named("90000003", 1);
        server.serve("90000001", Behaviour.OK, FakeTsdrServer.xml(liveCode, null, null))
                .serve("90000002", Behaviour.OK, FakeTsdrServer.xml(deadCode, null, null))
                .serve("90000003", Behaviour.NOT_FOUND);

        live(Map.of("max-marks-per-run", "2"));
        live(Map.of("max-marks-per-run", "2")); // the same instant: 01 and 02 were answered, only 03 is left
        assertThat(server.calls("90000001")).isEqualTo(1);
        assertThat(server.calls("90000002")).isEqualTo(1);
        assertThat(server.calls("90000003")).isEqualTo(1);
        assertThat(callResult("90000003")).isEqualTo("NOT_FOUND");

        // everything was answered (not found counts), so a run a day later asks about nothing
        clock.advance(Duration.ofDays(1));
        Result third = live(Map.of());
        assertThat(third.status()).isEqualTo(RunStatus.DONE);
        assertThat(server.requests()).hasSize(3);

        // past the recheck interval the marks are asked about again
        clock.advance(Duration.ofDays(31));
        server.clearRequests();
        check(2, 28, List.of("uspto:90000001")); // the window moved on: a fresh finding keeps it in
        live(Map.of());
        assertThat(server.calls("90000001")).isEqualTo(1);
    }

    @Test
    void aFailedMarkIsNotTriedAgainBeforeTheErrorIntervalAndTheRunIsIncomplete() throws Exception {
        for (int i = 11; i <= 14; i++) {
            mark("900000" + i, "Mark" + i);
            named("900000" + i, 20 - i);
        }
        server.serve("90000011", Behaviour.RATE_LIMITED_ONCE, FakeTsdrServer.xml(liveCode, null, null))
                .serve("90000012", Behaviour.SERVER_ERROR)
                .serve("90000013", Behaviour.STALL)
                .serve("90000014", Behaviour.GARBAGE);
        Map<String, String> settings = Map.of("download.read-timeout", "200ms", "download.max-attempts", "2");

        Result first = live(settings);

        assertThat(first.status()).isEqualTo(RunStatus.INCOMPLETE);
        assertThat(first.needsAttention()).isFalse();
        assertThat(callResult("90000011")).isEqualTo("OK");
        assertThat(callResult("90000012")).isEqualTo("ERROR");
        assertThat(callResult("90000013")).isEqualTo("TIMEOUT");
        assertThat(callResult("90000014")).isEqualTo("UNPARSEABLE");
        assertThat(rows("WHERE serial_number = '90000012'").get(0)).containsEntry("http_status", 500)
                .containsEntry("attempts", 2);

        server.clearRequests();
        live(settings);
        assertThat(server.requests()).as("nothing is asked again within error-retry-interval").isEmpty();

        clock.advance(Duration.ofDays(2));
        live(settings);
        assertThat(server.calls("90000012")).as("a failed mark is tried again, with its attempts").isEqualTo(2);
        assertThat(server.calls("90000011")).as("an answered mark is not asked again").isZero();
    }

    @Test
    void aWrongKeyStopsTheRunAtTheFirstMarkAndTheKeyIsNowhereInTheOutput() throws Exception {
        for (int i = 21; i <= 23; i++) {
            mark("900000" + i, "Mark" + i);
            named("900000" + i, 30 - i);
            server.serve("900000" + i, Behaviour.OK);
        }
        server.requireKey("a-different-key-0123456789");

        Result result = live(Map.of());

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.needsAttention()).isTrue();
        assertThat(server.requests()).hasSize(1);
        assertThat(rows("")).singleElement().satisfies(row -> assertThat(row).containsEntry("call_result", "REFUSED")
                .containsEntry("http_status", 401));
        assertEverywhereWithout(KEY, result);
    }

    @Test
    void aLimitThatLastsAndAStreakOfFailuresStopTheRun() throws Exception {
        for (int i = 31; i <= 35; i++) {
            mark("900000" + i, "Mark" + i);
            named("900000" + i, 40 - i);
        }
        server.serve("90000031", Behaviour.RATE_LIMITED_ALWAYS);
        Result limited = live(Map.of("download.max-attempts", "2"));
        assertThat(limited.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(limited.needsAttention()).isTrue();
        assertThat(callResult("90000031")).isEqualTo("RATE_LIMITED");
        assertThat(server.calls("90000032")).isZero();

        jdbc.execute("TRUNCATE tsdr_status, tsdr_status_run");
        server.clearRequests();
        for (int i = 31; i <= 35; i++) {
            server.serve("900000" + i, Behaviour.SERVER_ERROR);
        }
        Result streak = live(Map.of("max-consecutive-failures", "2", "download.max-attempts", "1"));
        assertThat(streak.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(rows("")).hasSize(2);
        assertThat(streak.notes()).anyMatch(note -> note.contains("in a row"));
    }

    @Test
    void anUnmatchedMarkIsAskedOnlyUpToItsOwnLimitAfterTheMatchedOnes() throws Exception {
        mark("90000041", "Hotel");
        named("90000041", 1);
        mark("90000042", "India");
        mark("90000043", "Juliet");
        for (String serial : new String[] {"90000041", "90000042", "90000043"}) {
            server.serve(serial, Behaviour.OK, FakeTsdrServer.xml(liveCode, null, null));
        }

        live(Map.of("max-marks-per-run", "5", "unmatched-per-run", "1"));

        assertThat(rows("")).extracting(row -> row.get("serial_number")).containsExactlyInAnyOrder("90000041", "90000042");
        assertThat(server.calls("90000043")).isZero();
    }

    @Test
    void aDryRunNamesTheMarksAndAsksAndWritesNothing() throws Exception {
        mark("90000051", "Kilo");
        named("90000051", 2);

        Result result = job(properties(Map.of()), false).run(new Options(null, null, true));

        assertThat(result.runId()).isNull();
        assertThat(result.notes()).anyMatch(note -> note.contains("uspto:90000051"));
        assertThat(server.requests()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tsdr_status_run", Integer.class)).isZero();
    }

    @Test
    void aLiveRunWithoutCallsOrKeyIsRefusedAndAnotherRunHoldingTheLockDoesNothing() throws Exception {
        mark("90000052", "Lima");
        named("90000052", 2);
        assertThatThrownBy(() -> job(properties(Map.of()), false).run(new Options(null, null, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("download.enabled");

        server.serve("90000052", Behaviour.OK);
        try (var held = lock.tryAcquire().orElseThrow()) {
            assertThat(held).isNotNull();
            Result result = live(Map.of());
            assertThat(result.needsAttention()).isTrue();
            assertThat(result.message()).contains("holds the lock");
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void offlineFilesAreReadOnceAndNothingIsAskedOverTheNetwork() throws Exception {
        mark("90000061", "Mike");
        named("90000061", 3);
        mark("90000062", "November");
        named("90000062", 2);
        Path saved = Files.createDirectories(catalogs.resolve("raw/saved"));
        Files.writeString(saved.resolve("sn90000061.xml"), FakeTsdrServer.xml(deadCode, "CANCELLED", "2023-03-04"));
        Files.writeString(saved.resolve("90000062.json"),
                "{\"status\":{\"status\":" + liveCode + ",\"statusDate\":\"20200102\"}}");
        Files.writeString(saved.resolve("sn90000063.xml"), "not a status document");
        Files.writeString(saved.resolve("notes.txt"), "left out");
        TsdrProperties off = properties(Map.of("download.enabled", "false"));

        Result first = job(off, false).run(new Options("saved", null, false));

        assertThat(first.status()).isEqualTo(RunStatus.INCOMPLETE);
        assertThat(first.notes()).anyMatch(n -> n.contains("notes.txt")).anyMatch(n -> n.contains("sn90000063.xml"));
        assertThat(rows("WHERE source = 'FILE'")).hasSize(3);
        assertThat(rows("WHERE serial_number = '90000061'").get(0)).containsEntry("status_class", "DEAD")
                .containsEntry("source", "FILE");
        assertThat(rows("WHERE serial_number = '90000062'").get(0)).containsEntry("status_class", "LIVE");
        assertThat(callResult("90000063")).isEqualTo("UNPARSEABLE");
        assertThat(server.requests()).isEmpty();
        JsonNode proposals = report(first).get("proposedExclusions");
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).get("serialNumber").asText()).isEqualTo("90000061");

        // the same files again: the same bytes are not read into the database twice
        Result second = job(off, false).run(new Options("saved", null, false));
        assertThat(rows("WHERE source = 'FILE'")).hasSize(3);
        assertThat(second.notes()).anyMatch(n -> n.contains("3 already stored unchanged"));

        // a changed file is a new check
        Files.writeString(saved.resolve("sn90000061.xml"), FakeTsdrServer.xml(liveCode, "REGISTERED", "2025-01-01"));
        job(off, false).run(new Options("saved", null, false));
        assertThat(rows("WHERE serial_number = '90000061' AND call_result = 'OK'")).hasSize(2);
    }

    @Test
    void theInputFolderMustBeAFolderOfCatalogsRawOutsideTheReports() throws Exception {
        Files.createDirectories(catalogs.resolve("raw/tsdr/out"));
        for (String bad : new String[] {"..", "../x", "/etc", "tsdr/out", "missing"}) {
            assertThatThrownBy(() -> job(properties(Map.of()), false).run(new Options(bad, null, false)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theReportListsTheDeadMarkThatFindingsStillNameAndProposesButChangesNothing() throws Exception {
        mark("90000071", "Oscar");
        named("90000071", 4);
        mark("90000072", "Papa");
        named("90000072", 1);
        mark("90000073", "Quebec");
        named("90000073", 1);
        mark("90000074", "Romeo");
        named("90000074", 1);
        server.serve("90000071", Behaviour.OK, FakeTsdrServer.xml(deadCode, "ABANDONED", "2024-05-06"))
                .serve("90000072", Behaviour.OK, FakeTsdrServer.xml(liveCode, "REGISTERED", "2020-01-02"))
                .serve("90000073", Behaviour.NOT_FOUND)
                .serve("90000074", Behaviour.OK, FakeTsdrServer.xml(unsettledCode, "ODD", null));
        String catalogBefore = catalogFingerprint();

        Result result = live(Map.of());

        JsonNode report = report(result);
        assertThat(report.get("proposedExclusions")).hasSize(1);
        JsonNode proposal = report.get("proposedExclusions").get(0);
        assertThat(proposal.get("sourceId").asText()).isEqualTo("uspto:90000071");
        assertThat(proposal.get("mark").asText()).isEqualTo("Oscar");
        assertThat(proposal.get("proposal").asText()).isEqualTo("REMOVE_AT_NEXT_SYNC");
        assertThat(proposal.get("statusClass").asText()).isEqualTo("DEAD");
        assertThat(report.get("counts").get("matchedMarks").asInt()).isEqualTo(4);
        assertThat(report.get("counts").get("live").asInt()).isEqualTo(1);
        assertThat(report.get("counts").get("notFound").asInt()).isEqualTo(1);
        assertThat(report.get("counts").get("unknownStatus").asInt()).isEqualTo(1);
        assertThat(report.get("unverified")).isNotEmpty();
        String csv = Files.readString(result.reportDir().resolve("dead-marks.csv"));
        assertThat(csv).contains("90000071").doesNotContain("90000072");
        // nothing is removed: the catalog is as it was and no activation was written
        assertThat(catalogFingerprint()).isEqualTo(catalogBefore);
        assertEverywhereWithout(KEY, result);
    }

    @Test
    void aReportCellThatStartsLikeAFormulaIsDefused() {
        assertThat(TsdrStatusReport.cell("=SUM(A1)")).isEqualTo("\"'=SUM(A1)\"");
        assertThat(TsdrStatusReport.cell("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(TsdrStatusReport.cell(null)).isEqualTo("\"\"");
    }

    @Test
    void onlyTheNewestReportsAreKept() throws Exception {
        mark("90000081", "Sierra");
        named("90000081", 1);
        server.serve("90000081", Behaviour.OK, FakeTsdrServer.xml(liveCode, null, null));
        for (int run = 0; run < 3; run++) {
            clock.advance(Duration.ofSeconds(10));
            live(Map.of("keep-reports", "2"));
        }
        try (Stream<Path> reports = Files.list(catalogs.resolve("raw/tsdr/out"))) {
            assertThat(reports.count()).isEqualTo(2);
        }
    }

    @Test
    void theEnumsAreTheConstraintsOfTheTables() {
        long run = store.startRun(Mode.LIVE, catalogId, "tsdr-test", NOW);
        for (Mode mode : Mode.values()) {
            store.startRun(mode, catalogId, "tsdr-test", NOW);
        }
        for (RunStatus status : RunStatus.values()) {
            store.finishRun(store.startRun(Mode.LIVE, catalogId, "tsdr-test", NOW), status, 0, 0, 0, "m", NOW);
        }
        for (CallResult result : CallResult.values()) {
            boolean ok = result == CallResult.OK;
            store.insert(run, new TsdrStatusStore.Row("90000091", Source.API, result, null, 1, ok ? "700" : null, null,
                    null, ok ? StatusClass.LIVE : null, ok ? "map" : null, null, NOW, null));
        }
        for (Source source : Source.values()) {
            store.insert(run, new TsdrStatusStore.Row("90000092", source, CallResult.NOT_FOUND, null, null, null, null, null,
                    null, null, null, NOW, null));
        }
        for (StatusClass statusClass : StatusClass.values()) {
            store.insert(run, new TsdrStatusStore.Row("90000093", Source.API, CallResult.OK, null, null, "700", null,
                    LocalDate.of(2020, 1, 2), statusClass, "map", null, NOW, null));
        }
        assertThat(rows("")).hasSize(CallResult.values().length + Source.values().length + StatusClass.values().length);
        // an OK answer must carry its code and class
        assertThatThrownBy(() -> store.insert(run, new TsdrStatusStore.Row("90000094", Source.API, CallResult.OK, null, null,
                null, null, null, null, null, null, NOW, null))).isInstanceOf(DataAccessException.class);
        // and a serial number is eight digits
        assertThatThrownBy(() -> store.insert(run, new TsdrStatusStore.Row("9000009", Source.API, CallResult.NOT_FOUND, null,
                null, null, null, null, null, null, null, NOW, null))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aStoredAnswerIsNeverChanged() {
        long run = store.startRun(Mode.LIVE, catalogId, "tsdr-test", NOW);
        store.insert(run, new TsdrStatusStore.Row("90000095", Source.API, CallResult.NOT_FOUND, 404, 1, null, null, null,
                null, null, null, NOW, null));
        assertThatThrownBy(() -> jdbc.update("UPDATE tsdr_status SET call_result = 'ERROR'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM tsdr_status")).hasMessageContaining("append-only");
    }

    // --- the optional status on a finding ---

    private TrademarkCheckService serviceWith(TsdrProperties properties) {
        return new TrademarkCheckService(normalizers, runtime, rulesets, checkStore, transactions, plans,
                new TsdrStatusOverlay(properties, store));
    }

    @Test
    void aFindingCarriesTheLastStatusOnlyWhenTheDeploymentTurnsItOnAndItChangesNothingElse() throws Exception {
        mark("90000101", "Tango");
        UUID check = check(1, 29, List.of("uspto:90000101"), List.of("etsy:5"), List.of("uspto:90000102"));
        long run = store.startRun(Mode.OFFLINE, catalogId, "tsdr-test", NOW);
        store.insert(run, new TsdrStatusStore.Row("90000101", Source.FILE, CallResult.OK, null, null, deadCode,
                "ABANDONED", LocalDate.of(2024, 5, 6), StatusClass.DEAD, statusMap.version(), hex(), NOW.minusSeconds(60), null));
        store.insert(run, new TsdrStatusStore.Row("90000101", Source.API, CallResult.OK, 200, 1, liveCode, "REGISTERED",
                LocalDate.of(2025, 1, 1), StatusClass.LIVE, statusMap.version(), null, NOW, null));
        store.insert(run, new TsdrStatusStore.Row("90000101", Source.API, CallResult.TIMEOUT, null, 3, null, null, null, null,
                null, null, NOW.plusSeconds(60), "timeout")); // a later failure does not hide the last answer

        // off (the default of the deployment): the answer has no such member at all
        TrademarkCheckResult off = checkService.get(clientId, check);
        assertThat(mapper.writeValueAsString(off)).doesNotContain("tsdrStatuses");
        assertThat(off.findings()).allSatisfy(f -> assertThat(f.tsdrStatuses()).isNull());

        // on: the newest OK status, with the date it was checked, on the finding that names the serial number
        TrademarkCheckResult on = serviceWith(properties(Map.of("finding-status.enabled", "true"))).get(clientId, check);
        TrademarkCheckResult.FindingView named = on.findings().get(0);
        assertThat(named.tsdrStatuses()).singleElement().satisfies(status -> {
            assertThat(status.sourceId()).isEqualTo("uspto:90000101");
            assertThat(status.statusCode()).isEqualTo(liveCode);
            assertThat(status.statusClass()).isEqualTo("LIVE");
            assertThat(status.statusDate()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(status.checkedAt()).isEqualTo(NOW);
        });
        assertThat(on.findings().get(1).tsdrStatuses()).isEmpty();
        assertThat(on.findings().get(2).tsdrStatuses()).as("a serial number never checked").isEmpty();
        assertThat(mapper.readTree(mapper.writeValueAsString(on)).at("/findings/0/tsdrStatuses/0/checkedAt").asText())
                .isEqualTo(NOW.toString());

        // nothing that decides or identifies the result differs
        assertThat(on.withFindings(on.findings().stream().map(f -> f.withTsdrStatuses(null)).toList())).isEqualTo(off);
        assertThat(on.analysisVersion()).isEqualTo(off.analysisVersion());
        assertThat(on.riskLevel()).isEqualTo(off.riskLevel());
        assertThat(on.findings()).extracting(TrademarkCheckResult.FindingView::risk)
                .isEqualTo(off.findings().stream().map(TrademarkCheckResult.FindingView::risk).toList());
    }

    // --- checks over the output ---

    private String catalogFingerprint() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM trademark_catalog_version) || ':' || (SELECT count(*) FROM trademark_entry) || ':'
                    || (SELECT count(*) FROM trademark_catalog_activation) || ':' || coalesce((SELECT md5(string_agg(
                        array_to_string(source_ids, '|') || status || normalized, ',' ORDER BY id)) FROM trademark_entry), '')
                """, String.class);
    }

    private void assertEverywhereWithout(String secret, Result result) throws IOException {
        assertThat(jdbc.queryForList("SELECT * FROM tsdr_status").toString()).doesNotContain(secret);
        assertThat(jdbc.queryForList("SELECT * FROM tsdr_status_run").toString()).doesNotContain(secret);
        assertThat(result.message()).doesNotContain(secret);
        assertThat(result.notes().toString()).doesNotContain(secret);
        if (result.reportDir() != null) {
            try (Stream<Path> files = Files.list(result.reportDir())) {
                for (Path file : files.toList()) {
                    assertThat(Files.readString(file)).doesNotContain(secret);
                }
            }
        }
    }
}
