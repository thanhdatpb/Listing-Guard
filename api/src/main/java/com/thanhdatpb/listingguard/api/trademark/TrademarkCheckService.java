package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusOverlay;
import com.thanhdatpb.listingguard.api.trademark.judge.TrademarkContextJudge;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.trademark.CanonicalInput;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the trademark scanner on a listing and stores the result (ADR-009, ADR-010).
 *
 * <p>The request is the same envelope as {@code /checks} and goes through the same {@link Normalizer}, so a client sends
 * one JSON to both endpoints. The normalized listing is mapped to the scanner's input; a field the standard input does
 * not have (the shop name) is null. Only the canonical input hash and the result are stored, never the listing.
 *
 * <p>Cache identity is {@code (client, marketplace, pack, input hash, ruleset version, analysis version)}; the analysis
 * version is the pack's fingerprint (ruleset, algorithms, catalog, PostgreSQL major version, {@code pg_trgm} version,
 * marketplace). Only a complete, unexpired result answers a request. A result that is {@code INCOMPLETE} is stored so
 * that its id can be read again, and never reused. A complete result past its retention period is replaced: the expired
 * row and its findings are deleted and the new result inserted in one transaction. Two requests that replace the same
 * row at the same time are settled by the unique index: the one that loses rolls back and answers from the winner's
 * row; any other integrity failure is not a cache hit and is raised as it is.
 *
 * <p>The client's watchlist (ADR-031) is scanned in the same check. While it has an enabled entry, its hash, the watchlist
 * settings and the matcher version are part of the fingerprint, so a change of the list misses the cache; a client without one
 * keeps the fingerprint it always had. When no catalog is served and the client has a watchlist, the check runs the watchlist
 * and the rules that need no catalog and answers an INCOMPLETE result with {@code catalogStatus} NOT_CONFIGURED; without a
 * watchlist it is refused as before ({@link TrademarkUnavailableException}).
 */
@Service
public class TrademarkCheckService {

    private static final Logger log = LoggerFactory.getLogger(TrademarkCheckService.class);

    private final NormalizerRegistry normalizers;
    private final TrademarkCatalogRuntime runtime;
    private final TrademarkRulesets rulesets;
    private final TrademarkCheckStore store;
    private final TransactionTemplate transactions;
    private final PlanService plans;
    private final TsdrStatusOverlay tsdrStatus;
    private final WatchlistService watchlists;
    private final TrademarkContextJudge judge;

    /** A service whose answers never carry a TSDR status (the overlay is off). */
    public TrademarkCheckService(NormalizerRegistry normalizers, TrademarkCatalogRuntime runtime,
                                 TrademarkRulesets rulesets, TrademarkCheckStore store, TransactionTemplate transactions,
                                 PlanService plans) {
        this(normalizers, runtime, rulesets, store, transactions, plans, null);
    }

    /** A service with a TSDR overlay and no watchlist. */
    public TrademarkCheckService(NormalizerRegistry normalizers, TrademarkCatalogRuntime runtime,
                                 TrademarkRulesets rulesets, TrademarkCheckStore store, TransactionTemplate transactions,
                                 PlanService plans, TsdrStatusOverlay tsdrStatus) {
        this(normalizers, runtime, rulesets, store, transactions, plans, tsdrStatus, null);
    }

    /** A service whose answers are never judged by a language model: the checks of before ADR-036. */
    public TrademarkCheckService(NormalizerRegistry normalizers, TrademarkCatalogRuntime runtime,
                                 TrademarkRulesets rulesets, TrademarkCheckStore store, TransactionTemplate transactions,
                                 PlanService plans, TsdrStatusOverlay tsdrStatus, WatchlistService watchlists) {
        this(normalizers, runtime, rulesets, store, transactions, plans, tsdrStatus, watchlists, TrademarkContextJudge.off());
    }

    @Autowired
    public TrademarkCheckService(NormalizerRegistry normalizers, TrademarkCatalogRuntime runtime,
                                 TrademarkRulesets rulesets, TrademarkCheckStore store, TransactionTemplate transactions,
                                 PlanService plans, TsdrStatusOverlay tsdrStatus, WatchlistService watchlists,
                                 TrademarkContextJudge judge) {
        this.judge = judge;
        this.watchlists = watchlists;
        this.tsdrStatus = tsdrStatus;
        this.normalizers = normalizers;
        this.runtime = runtime;
        this.rulesets = rulesets;
        this.store = store;
        this.transactions = transactions;
        this.plans = plans;
    }

    /**
     * @throws TrademarkUnavailableException if no catalog is served (DISABLED or REFUSED)
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code trademark-risk} pack
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException  if the answer is not stored yet and the month's quota is used up
     */
    public TrademarkCheckResult check(UUID clientId, CheckRequest request) {
        return check(clientId, request, null);
    }

    /** The request of {@code POST /api/v1/trademark-checks}: the {@code /checks} envelope and an optional product type. */
    public TrademarkCheckResult check(UUID clientId, TrademarkCheckRequest request) {
        return check(clientId, new CheckRequest(request.marketplace(), request.listing()), request.productType());
    }

    /**
     * @param requestedProductType a product type the ruleset declares, or null or blank for none (ADR-009)
     * @throws com.thanhdatpb.listingguard.trademark.config.UnknownProductTypeException if the ruleset does not declare it;
     *         the request is refused before the cache, the quota or any work, and before the catalog is looked at
     */
    public TrademarkCheckResult check(UUID clientId, CheckRequest request, String requestedProductType) {
        // the last TSDR status is added to the answer only (ADR-009, "TSDR status check"): never stored, never cached, and
        // nothing when the deployment has not turned it on
        return withTsdrStatus(checkStored(clientId, request, requestedProductType));
    }

    private TrademarkCheckResult checkStored(UUID clientId, CheckRequest request, String requestedProductType) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.TRADEMARK_RISK);
        Normalizer normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        // an undeclared product type is the client's mistake: refuse it, never scan as if none had been named
        String productType = productType(requestedProductType);
        WatchlistService.Active watchlist = watchlists == null ? WatchlistService.Active.NONE : watchlists.active(clientId);
        // the watchlist and, when the LLM judge is on (ADR-036), its prompt and models are part of the analysis version; with both
        // absent the map is empty and the fingerprint is the one a check always had
        Map<String, String> watchlistComponents = new java.util.TreeMap<>(watchlist.watchlist().fingerprintComponents());
        watchlistComponents.putAll(judge.fingerprintComponents());
        TrademarkScanner scanner;
        String fingerprint;
        Long catalogVersionId;
        if (runtime.ready() || watchlist.watchlist().isEmpty()) {
            // without a watchlist an unserved catalog is refused here, exactly as before ADR-031
            // a client without a watchlist asks exactly as before (the one-argument form), so its fingerprint cannot move
            TrademarkCatalogRuntime.Serving serving = watchlistComponents.isEmpty()
                    ? runtime.serving(marketplace) : runtime.serving(marketplace, watchlistComponents);
            scanner = serving.scanner();
            fingerprint = serving.fingerprint().value();
            catalogVersionId = serving.catalogVersionId();
        } else {
            TrademarkCatalogRuntime.Unserved unserved = runtime.withoutCatalog(marketplace, watchlistComponents);
            scanner = unserved.scanner();
            fingerprint = unserved.fingerprint().value();
            catalogVersionId = null;
        }

        TrademarkCheckInput input = input(listing, productType);
        TrademarkCheckStore.Identity identity = new TrademarkCheckStore.Identity(clientId, marketplace,
                scanner.ruleset().packId(), CanonicalInput.of(input).hash(),
                rulesets.rulesetVersionId(marketplace), fingerprint);

        Optional<TrademarkCheckResult> cached = store.findCached(identity);
        if (cached.isPresent()) {
            return cached.get();
        }

        // a cached answer costs nothing and was returned above; a new result is stored, and a complete one is counted
        // (ADR-013). An INCOMPLETE result is stored for GET and never reused, so the client is not charged for it.
        plans.requireQuota(admission);
        TrademarkResult result = judge.judge(input, scanner.scan(input, watchlist.watchlist()), scanner.catalog());
        try {
            return transactions.execute(status -> {
                if (result.completeness() == Completeness.COMPLETE) {
                    plans.charge(admission);
                }
                UUID id = store.insert(identity, catalogVersionId, result, productType, watchlist);
                return store.find(clientId, id).orElseThrow();
            });
        } catch (DataIntegrityViolationException e) {
            if (result.completeness() == Completeness.COMPLETE && TrademarkCheckStore.isCacheIdentityViolation(e)) {
                // another request of this client stored the same complete result between our lookup and our insert
                log.debug("Concurrent identical trademark check for {}; answering from the stored result", marketplace);
                return store.findCached(identity).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * A retained check of this client. One that belongs to another client, one that never existed and one past its
     * retention period are reported exactly alike, so the answer does not reveal which ids are in use.
     */
    public TrademarkCheckResult get(UUID clientId, UUID id) {
        return withTsdrStatus(store.find(clientId, id).orElseThrow(() -> new TrademarkCheckNotFoundException(id)));
    }

    /**
     * Whether a trademark check of this client can be answered now: a catalog is served, or the client has a watchlist that
     * the check can run without one (ADR-031). The batch job and the console use it before they call {@link #check}.
     */
    public boolean available(UUID clientId) {
        return runtime.ready() || (watchlists != null && !watchlists.active(clientId).watchlist().isEmpty());
    }

    private TrademarkCheckResult withTsdrStatus(TrademarkCheckResult result) {
        return tsdrStatus == null ? result : tsdrStatus.apply(result);
    }

    /**
     * The text the scanner reads, from the normalized listing: title, tags, bullet points, description, brand and search
     * terms. The category is the Etsy category or the Amazon product type; it takes part in the hash and is not scanned.
     * The shop name is not in the standard input (docs/input-schema.md), so it is null.
     */
    static TrademarkCheckInput input(Listing listing) {
        return input(listing, null);
    }

    /**
     * The same with the request's product type (ADR-009), which is not the listing's own {@code productType}: that one is the
     * Amazon product type, only hashed as the category.
     */
    public static TrademarkCheckInput input(Listing listing, String productType) {
        String category = listing.category() != null ? listing.category() : listing.productType();
        return new TrademarkCheckInput(listing.title(), listing.tags(), listing.bulletPoints(), listing.description(),
                listing.brand(), null, listing.searchTerms(), category, productType);
    }

    /** The product type as the ruleset keys it (stripped), null for none; a type the ruleset does not declare is refused. */
    private String productType(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String type = requested.strip();
        rulesets.ruleset().productClasses().classesOf(type);
        return type;
    }
}
