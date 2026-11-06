package com.thanhdatpb.listingguard.api.variation;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.variation.CanonicalInput;
import com.thanhdatpb.listingguard.variation.Completeness;
import com.thanhdatpb.listingguard.variation.VariationChecker;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.VariationResult;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks a variation family and stores the result (ADR-015, ADR-010).
 *
 * <p>The request is its own shape (not the {@code /checks} envelope); only the marketplace code goes through the
 * {@link NormalizerRegistry}, so an unknown or planned marketplace is answered exactly as for the other endpoints. Only the
 * canonical input hash and the result are stored, never the family.
 *
 * <p>Cache identity is {@code (client, marketplace, pack, input hash, ruleset version, analysis version)}; the analysis
 * version is the pack's fingerprint (ruleset, comparison logic, input format, marketplace). Only a complete, unexpired result
 * answers a request. A result that is {@code INCOMPLETE} is stored so that its id can be read again, and never reused, and is
 * not charged. A complete result past its retention period is replaced: the expired row and its findings are deleted and the
 * new result inserted in one transaction. Two requests that replace the same row at the same time are settled by the unique
 * index: the one that loses rolls back and answers from the winner's row; any other integrity failure is not a cache hit and
 * is raised as it is.
 *
 * <p>Plans (ADR-013), in this order: the pack, the cache, the quota before the analysis, and the charge inside the
 * transaction that stores a complete result.
 */
@Service
public class VariationCheckService {

    private static final Logger log = LoggerFactory.getLogger(VariationCheckService.class);

    private final NormalizerRegistry marketplaces;
    private final VariationNormalizer normalizer;
    private final VariationRulesets rulesets;
    private final VariationCheckStore store;
    private final TransactionTemplate transactions;
    private final PlanService plans;

    public VariationCheckService(NormalizerRegistry marketplaces, VariationNormalizer normalizer, VariationRulesets rulesets,
                                 VariationCheckStore store, TransactionTemplate transactions, PlanService plans) {
        this.marketplaces = marketplaces;
        this.normalizer = normalizer;
        this.rulesets = rulesets;
        this.store = store;
        this.transactions = transactions;
        this.plans = plans;
    }

    /**
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code variation-checks} pack
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException  if the answer is not stored yet and the month's quota is used up
     */
    public VariationCheckResult check(UUID clientId, VariationCheckRequest request) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.VARIATION_CHECKS);
        Normalizer listingNormalizer = marketplaces.get(request.marketplace());
        String marketplace = listingNormalizer.marketplace().toUpperCase(Locale.ROOT);
        VariationInput input = normalizer.normalize(marketplace, request);
        VariationChecker checker = rulesets.checker();

        VariationCheckStore.Identity identity = new VariationCheckStore.Identity(clientId, marketplace,
                rulesets.ruleset().packId(), CanonicalInput.of(input).hash(), rulesets.rulesetVersionId(marketplace),
                checker.fingerprint(marketplace).value());

        Optional<VariationCheckResult> cached = store.findCached(identity);
        if (cached.isPresent()) {
            return cached.get();
        }

        // a cached answer costs nothing and was returned above; a new result is stored, and a complete one is counted
        // (ADR-013). An INCOMPLETE result is stored for GET and never reused, so the client is not charged for it.
        plans.requireQuota(admission);
        VariationResult result = checker.check(input);
        try {
            return transactions.execute(status -> {
                if (result.completeness() == Completeness.COMPLETE) {
                    plans.charge(admission);
                }
                UUID id = store.insert(identity, result);
                return store.find(clientId, id).orElseThrow();
            });
        } catch (DataIntegrityViolationException e) {
            if (result.completeness() == Completeness.COMPLETE && VariationCheckStore.isCacheIdentityViolation(e)) {
                // another request of this client stored the same complete result between our lookup and our insert
                log.debug("Concurrent identical variation check for {}; answering from the stored result", marketplace);
                return store.findCached(identity).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * A retained check of this client. One that belongs to another client, one that never existed and one past its retention
     * period are reported exactly alike, so the answer does not reveal which ids are in use.
     */
    public VariationCheckResult get(UUID clientId, UUID id) {
        return store.find(clientId, id).orElseThrow(() -> new VariationCheckNotFoundException(id));
    }
}
