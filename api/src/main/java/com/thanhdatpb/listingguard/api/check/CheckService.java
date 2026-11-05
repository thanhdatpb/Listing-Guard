package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScore;
import com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Normalizes the listing, runs the rule engine of its marketplace and stores the check with its findings.
 *
 * <p>The analysis version identifies everything that decides the result. Today that is the ruleset version;
 * when other analyzers are added it grows to include their versions, and the stored result stays valid only
 * for an identical analysis version. A listing that the same client already checked with the current analysis
 * version gets the stored result back (cached); another client's checks are never read or reused (ADR-007). Two
 * identical requests of one client at the same time are settled by a unique index: the request that loses the
 * race reads the winner's row and answers from it as well.
 */
@Service
public class CheckService {

    private static final Logger log = LoggerFactory.getLogger(CheckService.class);

    private final NormalizerRegistry normalizers;
    private final ContentHasher hasher;
    private final RuleEngine ruleEngine;
    private final ActiveRulesets rulesets;
    private final ListingCheckRepository checks;
    private final FindingRepository findings;
    private final RulesetVersionRepository rulesetVersions;
    private final TransactionTemplate transactions;
    private final PlanService plans;
    private final FixesProperties fixes;
    private final ReadinessScorer readinessScorer;

    public CheckService(NormalizerRegistry normalizers, ContentHasher hasher, RuleEngine ruleEngine,
                        ActiveRulesets rulesets, ListingCheckRepository checks, FindingRepository findings,
                        RulesetVersionRepository rulesetVersions, TransactionTemplate transactions, PlanService plans,
                        FixesProperties fixes, ReadinessScorer readinessScorer) {
        this.normalizers = normalizers;
        this.hasher = hasher;
        this.ruleEngine = ruleEngine;
        this.rulesets = rulesets;
        this.checks = checks;
        this.findings = findings;
        this.rulesetVersions = rulesetVersions;
        this.transactions = transactions;
        this.plans = plans;
        this.fixes = fixes;
        this.readinessScorer = readinessScorer;
    }

    /**
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code listing-rules} pack
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException  if the answer is not stored yet and the month's quota is used up
     */
    public CheckResult check(UUID clientId, CheckRequest request) {
        return check(clientId, request, fixes.enabled());
    }

    /**
     * Like {@link #check}, but always verifies fixes and scores readiness, whatever {@code listing-guard.fixes.enabled}
     * says (ADR-014, part B3: the console editor). The flag keeps deciding what the API and the batch job compute and
     * expose; this method changes neither. A stored result that has no fixes yet is enriched the way a cache hit of the
     * API is (no quota, same id, same hash).
     *
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code listing-rules} pack
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException  if the answer is not stored yet and the month's quota is used up
     */
    public CheckResult checkDetailed(UUID clientId, CheckRequest request) {
        return check(clientId, request, true);
    }

    private CheckResult check(UUID clientId, CheckRequest request, boolean detailed) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.LISTING_RULES);
        Normalizer normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        String contentHash = hasher.hash(listing);
        ActiveRulesets.Active ruleset = rulesets.get(RulesetConfig.DEFAULT_PACK_ID, marketplace);
        long rulesetVersionId = ruleset.rulesetVersionId();
        String packId = ruleset.config().packId();
        String analysisVersion = ruleset.config().version();

        Optional<CheckResult> cached = findCachedFor(clientId, marketplace, packId, contentHash, rulesetVersionId,
                analysisVersion, detailed, listing, ruleset.config());
        if (cached.isPresent()) {
            return cached.get();
        }

        // a cached answer costs nothing and was returned above; from here a new result is stored, and counted (ADR-013)
        plans.requireQuota(admission);
        ReadinessScore readiness = null;
        List<Finding> found;
        if (detailed) {
            RuleRun run = ruleEngine.runDetailed(listing, ruleset.config());
            found = run.findings();
            readiness = readinessScorer.score(run);
        } else {
            found = ruleEngine.run(listing, ruleset.config());
        }
        ReadinessScore storedReadiness = readiness;
        try {
            UUID id = transactions.execute(status -> persist(admission, packId, marketplace, contentHash,
                    rulesetVersionId, analysisVersion, found, storedReadiness));
            return toResult(checks.findById(id).orElseThrow(), false, detailed);
        } catch (DataIntegrityViolationException e) {
            // another request of this client stored the same listing between our lookup and our insert: answer from its row
            log.debug("Concurrent identical check for {} {}; answering from the stored result", marketplace, contentHash);
            return findCachedFor(clientId, marketplace, packId, contentHash, rulesetVersionId, analysisVersion, detailed,
                    listing, ruleset.config()).orElseThrow(() -> e);
        }
    }

    /**
     * A check of this client. One that belongs to another client is reported exactly like one that does not exist,
     * so the answer does not reveal which ids are in use.
     */
    public CheckResult get(UUID clientId, UUID id) {
        return toResult(checks.findByIdAndClientId(id, clientId).orElseThrow(() -> new CheckNotFoundException(id)), false,
                fixes.enabled());
    }

    /** The stored answer for this listing; when {@code detailed} and it has no fixes yet, it is enriched first. */
    private Optional<CheckResult> findCachedFor(UUID clientId, String marketplace, String packId, String contentHash,
                                                long rulesetVersionId, String analysisVersion, boolean detailed,
                                                Listing listing, RulesetConfig config) {
        Optional<CheckResult> cached = findCached(clientId, marketplace, packId, contentHash, rulesetVersionId,
                analysisVersion, detailed);
        if (cached.isPresent() && detailed && !Boolean.TRUE.equals(cached.get().fixesComputed())) {
            enrichCached(cached.get().checkId(), listing, config);
            return findCached(clientId, marketplace, packId, contentHash, rulesetVersionId, analysisVersion, true);
        }
        return cached;
    }

    private Optional<CheckResult> findCached(UUID clientId, String marketplace, String packId, String contentHash,
                                             long rulesetVersionId, String analysisVersion, boolean detailed) {
        return checks.findFirstByClientIdAndMarketplaceCodeAndPackIdAndContentHashAndRulesetVersionIdAndAnalysisVersionAndStatus(
                clientId, marketplace, packId, contentHash, rulesetVersionId, analysisVersion, CheckStatus.COMPLETED)
                .map(check -> toResult(check, true, detailed));
    }

    /**
     * Counts the result and stores it in one transaction (ADR-013): if the insert loses the race to an identical request
     * and rolls back, so does the charge.
     */
    private UUID persist(PlanService.Admission admission, String packId, String marketplace, String contentHash,
                         long rulesetVersionId, String analysisVersion, List<Finding> found, ReadinessScore readiness) {
        UUID clientId = admission.clientId();
        plans.charge(admission);
        ListingCheck saved = checks.saveAndFlush(new ListingCheck(clientId, packId, contentHash, marketplace,
                CheckStatus.COMPLETED, rulesetVersionId, analysisVersion));
        if (readiness != null) {
            saved.setDetailedAnalysis(readiness.status().name(), readiness.score(), readiness.ready(), readiness.version());
        }
        findings.saveAll(found.stream().map(finding -> FindingEntity.fromRule(saved.getId(), finding, rulesetVersionId)).toList());
        return saved.getId();
    }

    private void enrichCached(UUID checkId, Listing listing, RulesetConfig config) {
        RuleRun run = ruleEngine.runDetailed(listing, config);
        ReadinessScore readiness = readinessScorer.score(run);
        transactions.executeWithoutResult(status -> {
            ListingCheck stored = checks.findByIdForUpdate(checkId).orElseThrow(() -> new CheckNotFoundException(checkId));
            if (stored.isFixesComputed()) {
                return;
            }
            stored.setDetailedAnalysis(readiness.status().name(), readiness.score(), readiness.ready(), readiness.version());
            List<FindingEntity> storedFindings = findings.findByCheckIdOrderByIdAsc(checkId);
            if (storedFindings.size() != run.findings().size()) {
                throw new IllegalStateException("Cannot enrich cached check " + checkId
                        + ": stored and recomputed finding counts differ");
            }
            for (int i = 0; i < storedFindings.size(); i++) {
                FindingEntity entity = storedFindings.get(i);
                Finding computed = run.findings().get(i);
                if (!Objects.equals(entity.getRuleId(), computed.ruleId())
                        || !Objects.equals(entity.getField(), computed.field())
                        || !Objects.equals(entity.getValue(), computed.value())
                        || entity.getSeverity() != computed.severity()
                        || !Objects.equals(entity.getMessage(), computed.message())
                        || !Objects.equals(entity.getSuggestion(), computed.suggestion())) {
                    throw new IllegalStateException("Cannot enrich cached check " + checkId
                            + ": stored finding order or content differs from the recomputed result");
                }
                entity.setFix(computed.fix());
            }
            checks.save(stored);
            findings.saveAll(storedFindings);
        });
    }

    /**
     * {@code detailed} says whether this caller sees fixes and readiness. A result that was stored with them is shown
     * without them to a caller that does not (the API and the batch job while the flag is off).
     */
    private CheckResult toResult(ListingCheck check, boolean cached, boolean detailed) {
        List<CheckResult.FindingView> views = findings.findByCheckIdOrderByIdAsc(check.getId()).stream()
                .map(entity -> new CheckResult.FindingView(entity.getRuleId(), entity.getSource(), entity.getSeverity(),
                        entity.getField(), entity.getValue(), entity.getMessage(), entity.getSuggestion(),
                        detailed ? fixView(entity.getFix()) : null))
                .toList();
        String rulesetVersion = rulesetVersions.findById(check.getRulesetVersionId()).map(RulesetVersion::getVersion)
                .orElse(null);
        return new CheckResult(check.getId(), check.getStatus(), check.getMarketplaceCode(), rulesetVersion,
                check.getAnalysisVersion(), check.getContentHash(), cached, check.getCreatedAt(),
                CheckResult.Summary.of(views), views, detailed ? check.isFixesComputed() : null,
                detailed ? readinessView(check) : null);
    }

    private static CheckResult.FixView fixView(FixSuggestion fix) {
        return fix == null ? null : new CheckResult.FixView(fix.action(), fix.description(), fix.proposedValue());
    }

    private static CheckResult.ReadinessView readinessView(ListingCheck check) {
        if (check.getReadinessStatus() == null) {
            return null;
        }
        return new CheckResult.ReadinessView(check.getReadinessScore(), Boolean.TRUE.equals(check.getReadinessReady()),
                check.getReadinessStatus(), check.getReadinessVersion());
    }
}
