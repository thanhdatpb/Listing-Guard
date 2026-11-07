package com.thanhdatpb.listingguard.api.personalization;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.personalization.CanonicalInput;
import com.thanhdatpb.listingguard.personalization.Completeness;
import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import com.thanhdatpb.listingguard.personalization.PersonalizationScreener;
import com.thanhdatpb.listingguard.personalization.ScreeningResult;
import com.thanhdatpb.listingguard.personalization.config.UnknownPrintProfileException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screens the text a buyer typed for a custom product and stores the result (ADR-016, ADR-010).
 *
 * <p>The request is its own shape (not the {@code /checks} envelope); only the marketplace code goes through the
 * {@link NormalizerRegistry}, so an unknown or planned marketplace is answered exactly as for the other endpoints. Only the
 * canonical input hash and the result are stored, never the text. Log lines in this class never contain a value.
 *
 * <p>The trademark scanner runs on the catalog the application serves. A catalog that is {@code DISABLED} or {@code REFUSED}
 * is not an error here: the character rules run, and the trademark part is {@code NOT_CONFIGURED} and the result
 * {@code INCOMPLETE} (not reused, not charged).
 *
 * <p>Cache identity is {@code (client, marketplace, pack, input hash, ruleset version, analysis version)}; the analysis
 * version is the pack's fingerprint (ruleset, code versions, marketplace, trademark scan). Only a complete, unexpired result
 * answers a request. An {@code INCOMPLETE} result is stored so that its id can be read again, and never reused. A complete
 * result past its retention period is replaced: the expired row and its findings are deleted and the new result inserted in
 * one transaction. Two requests that replace the same row at the same time are settled by the unique index: the one that
 * loses rolls back and answers from the winner's row; any other integrity failure is not a cache hit and is raised as it is.
 *
 * <p>Plans (ADR-013), in this order: the pack, the cache, the quota before the analysis, and the charge inside the
 * transaction that stores a complete result.
 */
@Service
public class PersonalizationScreeningService {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationScreeningService.class);

    private final NormalizerRegistry marketplaces;
    private final PersonalizationNormalizer normalizer;
    private final PersonalizationRulesets rulesets;
    private final TrademarkCatalogRuntime trademarkRuntime;
    private final PersonalizationScreeningStore store;
    private final TransactionTemplate transactions;
    private final PlanService plans;

    public PersonalizationScreeningService(NormalizerRegistry marketplaces, PersonalizationNormalizer normalizer,
                                           PersonalizationRulesets rulesets, TrademarkCatalogRuntime trademarkRuntime,
                                           PersonalizationScreeningStore store, TransactionTemplate transactions,
                                           PlanService plans) {
        this.marketplaces = marketplaces;
        this.normalizer = normalizer;
        this.rulesets = rulesets;
        this.trademarkRuntime = trademarkRuntime;
        this.store = store;
        this.transactions = transactions;
        this.plans = plans;
    }

    /**
     * @throws UnknownPrintProfileException if the request names a print profile the ruleset does not declare
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code personalization-screening} pack
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException  if the answer is not stored yet and the month's quota is used up
     */
    public PersonalizationScreeningResult check(UUID clientId, PersonalizationScreeningRequest request) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.PERSONALIZATION_SCREENING);
        Normalizer listingNormalizer = marketplaces.get(request.marketplace());
        String marketplace = listingNormalizer.marketplace().toUpperCase(Locale.ROOT);
        PersonalizationInput input = normalizer.normalize(request);
        PersonalizationScreener screener = rulesets.screener();
        // an undeclared print profile is the client's mistake: refuse it before the cache, the quota or any work
        screener.ruleset().profile(input.printProfile());

        TrademarkCatalogRuntime.Serving serving = serving(marketplace);
        PersonalizationScreeningStore.Identity identity = new PersonalizationScreeningStore.Identity(clientId, marketplace,
                screener.ruleset().packId(), CanonicalInput.of(input).hash(), rulesets.rulesetVersionId(marketplace),
                screener.fingerprint(marketplace, serving == null ? null : serving.fingerprint().value()).value());

        Optional<PersonalizationScreeningResult> cached = store.findCached(identity);
        if (cached.isPresent()) {
            return cached.get();
        }

        // a cached answer costs nothing and was returned above; a new result is stored, and a complete one is counted
        // (ADR-013). An INCOMPLETE result is stored for GET and never reused, so the client is not charged for it.
        plans.requireQuota(admission);
        ScreeningResult result = screener.screen(input, serving == null ? null : serving.scanner());
        try {
            return transactions.execute(status -> {
                if (result.completeness() == Completeness.COMPLETE) {
                    plans.charge(admission);
                }
                UUID id = store.insert(identity, result);
                return store.find(clientId, id).orElseThrow();
            });
        } catch (DataIntegrityViolationException e) {
            if (result.completeness() == Completeness.COMPLETE && PersonalizationScreeningStore.isCacheIdentityViolation(e)) {
                // another request of this client stored the same complete result between our lookup and our insert
                log.debug("Concurrent identical personalization screening for {}; answering from the stored result",
                        marketplace);
                return store.findCached(identity).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * A retained screening of this client. One that belongs to another client, one that never existed and one past its
     * retention period are reported exactly alike, so the answer does not reveal which ids are in use.
     */
    public PersonalizationScreeningResult get(UUID clientId, UUID id) {
        return store.find(clientId, id).orElseThrow(() -> new PersonalizationScreeningNotFoundException(id));
    }

    /** The trademark catalog and scanner of this marketplace, or null when none is served (the scan then does not run). */
    private TrademarkCatalogRuntime.Serving serving(String marketplace) {
        try {
            return trademarkRuntime.serving(marketplace);
        } catch (TrademarkUnavailableException | IllegalArgumentException e) {
            log.debug("No trademark catalog is served for {}: {}", marketplace, e.getMessage());
            return null;
        }
    }
}
