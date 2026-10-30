package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.trademark.CanonicalInput;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
public class TrademarkCheckService {

    private static final Logger log = LoggerFactory.getLogger(TrademarkCheckService.class);

    private final NormalizerRegistry normalizers;
    private final TrademarkCatalogRuntime runtime;
    private final TrademarkRulesets rulesets;
    private final TrademarkCheckStore store;
    private final TransactionTemplate transactions;

    public TrademarkCheckService(NormalizerRegistry normalizers, TrademarkCatalogRuntime runtime,
                                 TrademarkRulesets rulesets, TrademarkCheckStore store, TransactionTemplate transactions) {
        this.normalizers = normalizers;
        this.runtime = runtime;
        this.rulesets = rulesets;
        this.store = store;
        this.transactions = transactions;
    }

    /**
     * @throws TrademarkUnavailableException if no catalog is served (DISABLED or REFUSED)
     */
    public TrademarkCheckResult check(UUID clientId, CheckRequest request) {
        Normalizer normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        TrademarkCatalogRuntime.Serving serving = runtime.serving(marketplace);

        TrademarkCheckInput input = input(listing);
        TrademarkCheckStore.Identity identity = new TrademarkCheckStore.Identity(clientId, marketplace,
                serving.scanner().ruleset().packId(), CanonicalInput.of(input).hash(),
                rulesets.rulesetVersionId(marketplace), serving.fingerprint().value());

        Optional<TrademarkCheckResult> cached = store.findCached(identity);
        if (cached.isPresent()) {
            return cached.get();
        }

        TrademarkResult result = serving.scanner().scan(input);
        try {
            return transactions.execute(status -> {
                UUID id = store.insert(identity, serving.catalogVersionId(), result);
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
        return store.find(clientId, id).orElseThrow(() -> new TrademarkCheckNotFoundException(id));
    }

    /**
     * The text the scanner reads, from the normalized listing: title, tags, bullet points, description, brand and search
     * terms. The category is the Etsy category or the Amazon product type; it takes part in the hash and is not scanned.
     * The shop name is not in the standard input (docs/input-schema.md), so it is null.
     */
    static TrademarkCheckInput input(Listing listing) {
        String category = listing.category() != null ? listing.category() : listing.productType();
        return new TrademarkCheckInput(listing.title(), listing.tags(), listing.bulletPoints(), listing.description(),
                listing.brand(), null, listing.searchTerms(), category);
    }
}
