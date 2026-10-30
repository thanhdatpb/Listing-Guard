package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.trademark.CanonicalInput;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The service, the store and the cleanup job of the trademark pack against a real PostgreSQL (ADR-009, ADR-010): an
 * INCOMPLETE result is stored and never reused, every part of the cache identity matters, two requests that replace the
 * same expired result both get the winner's, an unrelated integrity failure is not a cache hit, and the cleanup deletes
 * in bounded batches under an advisory lock.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class TrademarkCheckServiceIntegrationTest {

    @Autowired
    TrademarkCheckService service;

    @Autowired
    TrademarkCheckStore store;

    @Autowired
    NormalizerRegistry normalizers;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TrademarkProperties properties;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ObjectMapper mapper;

    @TempDir
    Path dir;

    // ---- helpers

    private UUID client(String name) {
        return jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, name);
    }

    private UUID clientA() {
        return client(ApiTestKeys.CLIENT_1);
    }

    private UUID clientB() {
        return client(ApiTestKeys.CLIENT_2);
    }

    private CheckRequest request(String title) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        return new CheckRequest("ETSY", listing);
    }

    private static String unique() {
        return TrademarkCheckApiIntegrationTest.unique();
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE trademark_check SET created_at = now() - interval '31 days', "
                + "expires_at = now() - interval '1 day' WHERE id = ?", id);
    }

    private int rows(String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM trademark_check WHERE " + where, Integer.class, args);
    }

    private int countIn(String table, String column, List<UUID> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " IN (" + placeholders + ")",
                Integer.class, ids.toArray());
    }

    private String inputHash(CheckRequest request) {
        return CanonicalInput.of(TrademarkCheckService.input(normalizers.get(request.marketplace())
                .normalize(request.listing()))).hash();
    }

    /** A catalog that fails every lookup, so every rule that reads it fails. */
    private record FailingCatalog(String version) implements TrademarkCatalog {
        @Override
        public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
            throw new IllegalStateException("catalog unavailable");
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms, double candidateThreshold) {
            throw new IllegalStateException("catalog unavailable");
        }
    }

    /** Delegates, but makes the first lookup of each scan wait for the other scan while armed: both are then in flight. */
    private record BarrierCatalog(TrademarkCatalog delegate, CyclicBarrier barrier, AtomicBoolean armed)
            implements TrademarkCatalog {
        @Override
        public String version() {
            return delegate.version();
        }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
            if (armed.get()) {
                try {
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the two requests did not meet", e);
                }
            }
            return delegate.findExact(normalizedForms);
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms, double candidateThreshold) {
            return delegate.fuzzyCandidates(normalizedForms, candidateThreshold);
        }
    }

    private TrademarkCheckService serviceOver(TrademarkCatalogRuntime runtime, TrademarkRulesets rulesetsToUse) {
        return new TrademarkCheckService(normalizers, runtime, rulesetsToUse, store, transactions);
    }

    /** A runtime that serves the same catalog and fingerprint but scans over a different catalog implementation. */
    private TrademarkCatalogRuntime runtimeScanningOver(Function<TrademarkCatalogRuntime.Serving, TrademarkCatalog> catalog) {
        TrademarkCatalogRuntime custom = new TrademarkCatalogRuntime(properties, jdbc, transactions, rulesets) {
            @Override
            public Serving serving(String marketplace) {
                Serving real = super.serving(marketplace);
                return new Serving(new TrademarkScanner(rulesets.ruleset(), catalog.apply(real)), real.fingerprint(),
                        real.catalogVersionId(), real.catalogVersion());
            }
        };
        assertThat(custom.evaluate().state()).isEqualTo(TrademarkCatalogRuntime.State.READY);
        return custom;
    }

    // ---- INCOMPLETE

    @Test
    void anIncompleteResultKeepsTheKnownRiskIsStoredAndIsNeverReused() {
        TrademarkCheckService failing = serviceOver(runtimeScanningOver(real -> new FailingCatalog(real.catalogVersion())), rulesets);
        CheckRequest withFinding = request("Northvale Baseball Jersey " + unique());

        TrademarkCheckResult first = failing.check(clientA(), withFinding);
        TrademarkCheckResult second = failing.check(clientA(), withFinding);

        assertThat(first.completeness().name()).isEqualTo("INCOMPLETE");
        assertThat(first.failedRuleIds()).contains("catalog_exact", "catalog_phrase", "catalog_fuzzy", "pure_color_description");
        assertThat(first.findings()).extracting(TrademarkCheckResult.FindingView::ruleId).containsExactly("geo_sport_combination");
        assertThat(first.riskLevel().name()).as("the established MEDIUM is kept").isEqualTo("MEDIUM");
        assertThat(second.cached()).isFalse();
        assertThat(second.checkId()).as("an INCOMPLETE result never answers another request").isNotEqualTo(first.checkId());
        assertThat(service.get(clientA(), first.checkId()).completeness().name()).as("but GET reads it").isEqualTo("INCOMPLETE");
        assertThat(service.get(clientA(), first.checkId()).findings()).isEqualTo(first.findings());

        TrademarkCheckResult healthy = service.check(clientA(), withFinding);
        assertThat(healthy.cached()).as("a stored INCOMPLETE result is not a cache hit for the healthy analysis").isFalse();
        assertThat(healthy.completeness().name()).isEqualTo("COMPLETE");
        assertThat(healthy.analysisVersion()).isEqualTo(first.analysisVersion());
        TrademarkCheckResult again = service.check(clientA(), withFinding);
        assertThat(again.cached()).isTrue();
        assertThat(again.checkId()).isEqualTo(healthy.checkId());
        assertThat(rows("input_hash = ?", inputHash(withFinding))).isEqualTo(3);
    }

    @Test
    void anIncompleteResultWithNothingAboveLowIsUnknownNotLow() {
        TrademarkCheckService failing = serviceOver(runtimeScanningOver(real -> new FailingCatalog(real.catalogVersion())), rulesets);

        TrademarkCheckResult result = failing.check(clientA(), request("Plain mug " + unique()));

        assertThat(result.completeness().name()).isEqualTo("INCOMPLETE");
        assertThat(result.riskLevel().name()).isEqualTo("UNKNOWN");
        assertThat(result.findings()).isEmpty();
        assertThat(service.get(clientA(), result.checkId()).riskLevel().name()).isEqualTo("UNKNOWN");
    }

    // ---- cache identity

    @Test
    void everyPartOfTheCacheIdentityDecidesAHit() {
        CheckRequest request = request("Zorvex identity " + unique());
        TrademarkCheckResult stored = service.check(clientA(), request);
        TrademarkCheckStore.Identity same = new TrademarkCheckStore.Identity(clientA(), "ETSY", rulesets.ruleset().packId(),
                inputHash(request), rulesets.rulesetVersionId("ETSY"), stored.analysisVersion());

        assertThat(store.findCached(same)).map(TrademarkCheckResult::checkId).contains(stored.checkId());
        assertThat(store.findCached(new TrademarkCheckStore.Identity(clientB(), same.marketplace(), same.packId(),
                same.inputHash(), same.rulesetVersionId(), same.analysisVersion()))).as("client").isEmpty();
        assertThat(store.findCached(new TrademarkCheckStore.Identity(same.clientId(), "AMAZON", same.packId(),
                same.inputHash(), same.rulesetVersionId(), same.analysisVersion()))).as("marketplace").isEmpty();
        assertThat(store.findCached(new TrademarkCheckStore.Identity(same.clientId(), same.marketplace(), "image-compliance",
                same.inputHash(), same.rulesetVersionId(), same.analysisVersion()))).as("pack").isEmpty();
        assertThat(store.findCached(new TrademarkCheckStore.Identity(same.clientId(), same.marketplace(), same.packId(),
                "0".repeat(64), same.rulesetVersionId(), same.analysisVersion()))).as("input hash").isEmpty();
        assertThat(store.findCached(new TrademarkCheckStore.Identity(same.clientId(), same.marketplace(), same.packId(),
                same.inputHash(), same.rulesetVersionId() + 1_000_000, same.analysisVersion()))).as("ruleset version").isEmpty();
        assertThat(store.findCached(new TrademarkCheckStore.Identity(same.clientId(), same.marketplace(), same.packId(),
                same.inputHash(), same.rulesetVersionId(), "f".repeat(64)))).as("analysis version").isEmpty();
    }

    @Test
    void anotherCatalogVersionIsAMissAndTheOriginalStaysCached() {
        CheckRequest request = request("Zorvex catalog " + unique());
        TrademarkCheckResult original = service.check(clientA(), request);

        PreparedCatalog other = TrademarkFixtures.prepare(TrademarkFixtures.syntheticDir(dir, "endpoint-other-catalog"),
                "endpoint-other-catalog", rulesets.ruleset());
        importer.importCatalog(other);
        TrademarkCatalogRuntime otherRuntime = new TrademarkCatalogRuntime(new TrademarkProperties(properties.ruleset(),
                properties.marketplaces(), other.version(), properties.catalogsDir()), jdbc, transactions, rulesets);
        assertThat(otherRuntime.evaluate().state()).isEqualTo(TrademarkCatalogRuntime.State.READY);

        TrademarkCheckResult onOther = serviceOver(otherRuntime, rulesets).check(clientA(), request);

        assertThat(onOther.cached()).isFalse();
        assertThat(onOther.checkId()).isNotEqualTo(original.checkId());
        assertThat(onOther.catalogVersion()).isEqualTo(other.version()).isNotEqualTo(original.catalogVersion());
        assertThat(onOther.analysisVersion()).isNotEqualTo(original.analysisVersion());
        assertThat(service.check(clientA(), request).checkId()).isEqualTo(original.checkId());
    }

    @Test
    void anotherRulesetVersionIsAMissAndTheOriginalStaysCached() throws Exception {
        CheckRequest request = request("Zorvex ruleset " + unique());
        TrademarkCheckResult original = service.check(clientA(), request);

        String yaml = new ClassPathResource("rulesets/trademark-risk-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8);
        byte[] newerYaml = yaml.replace("version: trademark-risk-2026-11", "version: trademark-risk-2099-01")
                .replace("final_threshold: 0.80", "final_threshold: 0.85").getBytes(StandardCharsets.UTF_8);
        TrademarkRulesets newer = new TrademarkRulesets(new TrademarkProperties("classpath:ignored.yaml",
                List.of("ETSY", "AMAZON"), "", "catalogs"), new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(newerYaml);
            }
        }, versions, transactions);
        newer.afterSingletonsInstantiated();
        TrademarkCatalogRuntime newerRuntime = new TrademarkCatalogRuntime(properties, jdbc, transactions, newer);
        assertThat(newerRuntime.evaluate().state()).isEqualTo(TrademarkCatalogRuntime.State.READY);

        TrademarkCheckResult onNewer = serviceOver(newerRuntime, newer).check(clientA(), request);

        assertThat(onNewer.cached()).isFalse();
        assertThat(onNewer.rulesetVersion()).isEqualTo("trademark-risk-2099-01");
        assertThat(onNewer.checkId()).isNotEqualTo(original.checkId());
        assertThat(onNewer.analysisVersion()).isNotEqualTo(original.analysisVersion());
        assertThat(service.check(clientA(), request).checkId()).isEqualTo(original.checkId());
    }

    // ---- races and integrity

    private void raceTwoIdenticalRequests(boolean expireFirst) throws Exception {
        CheckRequest request = request("Zorvex race " + unique());
        UUID client = clientA();
        UUID oldId = null;
        if (expireFirst) {
            oldId = service.check(client, request).checkId();
            expire(oldId);
        }

        AtomicBoolean armed = new AtomicBoolean(true);
        CyclicBarrier barrier = new CyclicBarrier(2);
        TrademarkCheckService racing = serviceOver(runtimeScanningOver(real -> new BarrierCatalog(
                new PostgresTrademarkCatalog(jdbc, transactions, real.catalogVersionId(), real.catalogVersion()), barrier, armed)),
                rulesets);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<TrademarkCheckResult> call = () -> {
                start.await();
                return racing.check(client, request);
            };
            Future<TrademarkCheckResult> first = pool.submit(call);
            Future<TrademarkCheckResult> second = pool.submit(call);
            start.countDown();
            TrademarkCheckResult a = first.get(60, TimeUnit.SECONDS);
            TrademarkCheckResult b = second.get(60, TimeUnit.SECONDS);

            assertThat(a.checkId()).as("both get the winner's result").isEqualTo(b.checkId());
            assertThat(a.findings()).isEqualTo(b.findings());
            assertThat(List.of(a.cached(), b.cached())).as("one stored it, the other answered from it")
                    .containsExactlyInAnyOrder(false, true);
            assertThat(rows("input_hash = ? AND client_id = ?", inputHash(request), client)).isEqualTo(1);
            assertThat(service.get(client, a.checkId()).checkId()).isEqualTo(a.checkId());
            if (oldId != null) {
                assertThat(a.checkId()).isNotEqualTo(oldId);
                assertThat(rows("id = ?", oldId)).isZero();
            }
        } finally {
            armed.set(false);
            pool.shutdownNow();
        }
    }

    @Test
    void twoIdenticalRequestsReplacingTheSameExpiredResultBothGetTheWinnersResult() throws Exception {
        raceTwoIdenticalRequests(true);
    }

    @Test
    void twoIdenticalFirstRequestsBothGetTheWinnersResult() throws Exception {
        raceTwoIdenticalRequests(false);
    }

    @Test
    void onlyTheUniqueViolationOfTheCacheIdentityCountsAsACacheRace() {
        TrademarkCheckResult stored = service.check(clientA(), request("Zorvex violation " + unique()));
        String copy = """
                INSERT INTO trademark_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    catalog_version_id, analysis_version, completeness, risk_level, failed_rule_ids, disclaimer, created_at,
                    expires_at)
                SELECT gen_random_uuid(), %s, marketplace_code, pack_id, input_hash, ruleset_version_id, catalog_version_id,
                    analysis_version, completeness, risk_level, failed_rule_ids, disclaimer, created_at, expires_at
                FROM trademark_check WHERE id = ?""";

        // the same cache identity again: the race
        assertThatThrownBy(() -> jdbc.update(copy.formatted("client_id"), stored.checkId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(TrademarkCheckStore.isCacheIdentityViolation(e)).isTrue());
        // a client that does not exist: a foreign key, not a race
        assertThatThrownBy(() -> jdbc.update(copy.formatted("gen_random_uuid()"), stored.checkId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(TrademarkCheckStore.isCacheIdentityViolation(e)).isFalse());
    }

    @Test
    void anUnrelatedIntegrityFailureIsRaisedNotAnsweredAsACacheHit() {
        assertThatThrownBy(() -> service.check(UUID.randomUUID(), request("Zorvex orphan " + unique())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- retention cleanup

    private TrademarkRetentionCleanup cleanup(int batchSize) {
        return new TrademarkRetentionCleanup(jdbc, transactions, new TrademarkRetentionProperties(Duration.ofDays(30), true,
                Duration.ofHours(1), Duration.ofMinutes(1), batchSize));
    }

    private UUID storedCheck(String prefix) {
        return service.check(clientA(), request(prefix + " " + unique())).checkId();
    }

    @Test
    void theLockKeysAreTheReservedPair() {
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_1).isEqualTo(1279746628);
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_2).isEqualTo(1380271182);
    }

    @Test
    void cleanupDeletesExpiredChecksWithTheirFindingsInBoundedBatchesAndKeepsTheRest() {
        // expired rows other tests left behind would be deleted first (oldest expiry first): start from none
        cleanup(500).runOnce();
        List<UUID> expired = new ArrayList<>(List.of(storedCheck("Zorvex a"), storedCheck("Zorvex b"), storedCheck("Zorvex c"),
                storedCheck("Zorvex d"), storedCheck("Zorvex e")));
        UUID kept = storedCheck("Zorvex kept");
        expired.forEach(this::expire);
        assertThat(countIn("trademark_check_finding", "check_id", expired)).isPositive();

        TrademarkRetentionCleanup batchOfTwo = cleanup(2);
        assertThat(batchOfTwo.runBatch()).as("one batch is bounded by the batch size").isEqualTo(2);
        assertThat(countIn("trademark_check", "id", expired)).isEqualTo(3);

        TrademarkRetentionCleanup.Outcome outcome = batchOfTwo.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isGreaterThanOrEqualTo(3);
        assertThat(countIn("trademark_check", "id", expired)).isZero();
        assertThat(countIn("trademark_check_finding", "check_id", expired)).as("findings go with their check").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_check WHERE expires_at <= now()", Integer.class)).isZero();
        assertThat(service.get(clientA(), kept).checkId()).as("a retained check is untouched").isEqualTo(kept);
        assertThat(rows("id = ?", kept)).isEqualTo(1);
    }

    @Test
    void cleanupSkipsTheRunWhileAnotherInstanceHoldsTheAdvisoryLock() throws Exception {
        UUID expired = storedCheck("Zorvex locked");
        expire(expired);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = other.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(" + TrademarkRetentionCleanup.LOCK_KEY_1 + ", "
                        + TrademarkRetentionCleanup.LOCK_KEY_2 + ")");
                holding.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            TrademarkRetentionCleanup.Outcome skipped = cleanup(500).runOnce();

            assertThat(skipped.skipped()).isTrue();
            assertThat(skipped.deletedChecks()).isZero();
            assertThat(rows("id = ?", expired)).as("nothing was deleted while the lock was taken").isEqualTo(1);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            TrademarkRetentionCleanup.Outcome done = cleanup(500).runOnce();
            assertThat(done.skipped()).isFalse();
            assertThat(rows("id = ?", expired)).as("the next run, with the lock free, deletes it").isZero();
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    @Test
    void theCleanupLockIsReleasedWhenTheBatchEnds() {
        cleanup(500).runOnce();

        Boolean again = transactions.execute(status -> jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)",
                Boolean.class, TrademarkRetentionCleanup.LOCK_KEY_1, TrademarkRetentionCleanup.LOCK_KEY_2));
        assertThat(again).isTrue();
    }
}
