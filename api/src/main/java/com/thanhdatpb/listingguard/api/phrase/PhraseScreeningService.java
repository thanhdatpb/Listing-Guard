package com.thanhdatpb.listingguard.api.phrase;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.trademark.PhraseScreening;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogLookupLimitException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(PhraseScreeningProperties.class)
public class PhraseScreeningService {

    private final PhraseScreeningProperties properties;
    private final NormalizerRegistry normalizers;
    private final TrademarkCatalogRuntime runtime;
    private final WatchlistService watchlists;
    private final PlanService plans;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PhraseScreeningService(PhraseScreeningProperties properties, NormalizerRegistry normalizers,
                                  TrademarkCatalogRuntime runtime, WatchlistService watchlists,
                                  PlanService plans, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.normalizers = normalizers;
        this.runtime = runtime;
        this.watchlists = watchlists;
        this.plans = plans;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(timeout = 30)
    public PhraseScreeningResponse screen(AuthenticatedClient client, PhraseScreeningRequest request) {
        return screen(client.id(), client.keyId(), null, request);
    }

    /** Check as the signed-in console account resolved by the caller from the current tenant. */
    @Transactional(timeout = 30)
    public PhraseScreeningResponse screenConsole(UUID clientId, UUID accountId, PhraseScreeningRequest request) {
        return screen(clientId, null, java.util.Objects.requireNonNull(accountId), request);
    }

    private PhraseScreeningResponse screen(UUID clientId, String keyRef, UUID accountId, PhraseScreeningRequest request) {
        if (!properties.enabled()) {
            throw new PhraseScreeningException(HttpStatus.NOT_FOUND, "phrase-screening-disabled",
                    "Phrase screening is disabled.");
        }
        int submitted = request.phrases().size();
        if (submitted > properties.maxPhrases()) {
            throw new PhraseScreeningException(HttpStatus.BAD_REQUEST, "too-many-phrases",
                    "Send at most " + properties.maxPhrases() + " phrases per request.");
        }
        var admission = plans.requirePack(clientId, Pack.TRADEMARK_RISK);
        String marketplace = normalizers.get(request.marketplace() == null ? "ETSY" : request.marketplace())
                .marketplace().toUpperCase(Locale.ROOT);
        try {
            // Lock one client until the run and both quota counters are committed. Other clients can run independently.
            jdbc.queryForObject("SELECT id FROM client WHERE id = ? FOR NO KEY UPDATE", UUID.class, clientId);
            var startedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            var day = startedAt.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
            long used = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(phrase_count), 0) FROM phrase_screening_run
                    WHERE client_id = ? AND created_at >= ? AND created_at < ?
                    """, Long.class, clientId, day, day.plusDays(1));
            if (used + submitted > properties.maxPhrasesPerDay()) {
                throw new PhraseScreeningException(HttpStatus.TOO_MANY_REQUESTS, "phrase-daily-limit",
                        "Daily phrase limit exceeded. The limit resets at " + day.plusDays(1) + ".");
            }

            var activeWatchlist = watchlists.active(clientId);
            var watchlist = activeWatchlist.watchlist();
            var components = new TreeMap<>(watchlist.fingerprintComponents());
            components.put("watchlist-revision", Long.toString(activeWatchlist.revision()));
            components.put("phrase-screening", PhraseScreening.VERSION + "/fuzzy-" + properties.maxFuzzyPhrases());
            var serving = !runtime.ready() && !watchlist.isEmpty() ? null : runtime.serving(marketplace, components);
            var unserved = serving == null ? runtime.withoutCatalog(marketplace, components) : null;
            var scanner = serving == null ? unserved.scanner() : serving.scanner();
            var fingerprint = serving == null ? unserved.fingerprint() : serving.fingerprint();
            plans.requireQuota(admission);

            long started = System.nanoTime();
            var inputs = request.phrases().stream().map(row -> new PhraseScreening.Input(row.text(), row.searchVolume())).toList();
            var result = PhraseScreening.screen(scanner, inputs, request.productType(), watchlist, properties.maxFuzzyPhrases());
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            plans.charge(admission);
            var summary = result.summary();
            jdbc.update("""
                    INSERT INTO phrase_screening_run (client_id, api_key_ref, phrase_count, block_count, review_count,
                        clear_count, fuzzy_skipped_count, duration_ms, catalog_version, ruleset_version, created_at, account_id, marketplace_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, clientId, keyRef, submitted, summary.block(), summary.review(), summary.clear(),
                    summary.fuzzySkipped(), durationMillis, result.catalogVersion(), result.rulesetVersion(), startedAt, accountId, marketplace);
            return PhraseScreeningResponse.of(fingerprint.value(), marketplace, result);
        } catch (CatalogLookupLimitException | QueryTimeoutException e) {
            throw new PhraseScreeningException(HttpStatus.SERVICE_UNAVAILABLE, "phrase-screening-budget",
                    "Phrase screening exceeded its processing budget. Try a smaller batch.");
        }
    }
}
