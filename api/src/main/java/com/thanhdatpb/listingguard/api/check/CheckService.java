package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import java.util.List;
import java.util.Locale;
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

    public CheckService(NormalizerRegistry normalizers, ContentHasher hasher, RuleEngine ruleEngine,
                        ActiveRulesets rulesets, ListingCheckRepository checks, FindingRepository findings,
                        RulesetVersionRepository rulesetVersions, TransactionTemplate transactions) {
        this.normalizers = normalizers;
        this.hasher = hasher;
        this.ruleEngine = ruleEngine;
        this.rulesets = rulesets;
        this.checks = checks;
        this.findings = findings;
        this.rulesetVersions = rulesetVersions;
        this.transactions = transactions;
    }

    public CheckResult check(UUID clientId, CheckRequest request) {
        Normalizer normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        String contentHash = hasher.hash(listing);
        ActiveRulesets.Active ruleset = rulesets.get(RulesetConfig.DEFAULT_PACK_ID, marketplace);
        long rulesetVersionId = ruleset.rulesetVersionId();
        String packId = ruleset.config().packId();
        String analysisVersion = ruleset.config().version();

        Optional<CheckResult> cached = findCached(clientId, marketplace, contentHash, rulesetVersionId);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Finding> found = ruleEngine.run(listing, ruleset.config());
        try {
            UUID id = transactions.execute(status -> persist(clientId, packId, marketplace, contentHash,
                    rulesetVersionId, analysisVersion, found));
            return toResult(checks.findById(id).orElseThrow(), false);
        } catch (DataIntegrityViolationException e) {
            // another request of this client stored the same listing between our lookup and our insert: answer from its row
            log.debug("Concurrent identical check for {} {}; answering from the stored result", marketplace, contentHash);
            return findCached(clientId, marketplace, contentHash, rulesetVersionId).orElseThrow(() -> e);
        }
    }

    /**
     * A check of this client. One that belongs to another client is reported exactly like one that does not exist,
     * so the answer does not reveal which ids are in use.
     */
    public CheckResult get(UUID clientId, UUID id) {
        return toResult(checks.findByIdAndClientId(id, clientId).orElseThrow(() -> new CheckNotFoundException(id)), false);
    }

    private Optional<CheckResult> findCached(UUID clientId, String marketplace, String contentHash, long rulesetVersionId) {
        return checks.findFirstByClientIdAndMarketplaceCodeAndContentHashAndRulesetVersionIdAndStatus(
                clientId, marketplace, contentHash, rulesetVersionId, CheckStatus.COMPLETED).map(check -> toResult(check, true));
    }

    private UUID persist(UUID clientId, String packId, String marketplace, String contentHash, long rulesetVersionId,
                         String analysisVersion, List<Finding> found) {
        ListingCheck saved = checks.saveAndFlush(new ListingCheck(clientId, packId, contentHash, marketplace,
                CheckStatus.COMPLETED, rulesetVersionId, analysisVersion));
        findings.saveAll(found.stream().map(finding -> FindingEntity.fromRule(saved.getId(), finding, rulesetVersionId)).toList());
        return saved.getId();
    }

    private CheckResult toResult(ListingCheck check, boolean cached) {
        List<CheckResult.FindingView> views = findings.findByCheckIdOrderByIdAsc(check.getId()).stream()
                .map(entity -> new CheckResult.FindingView(entity.getRuleId(), entity.getSource(), entity.getSeverity(),
                        entity.getField(), entity.getValue(), entity.getMessage(), entity.getSuggestion()))
                .toList();
        String rulesetVersion = check.getRulesetVersionId() == null ? null
                : rulesetVersions.findById(check.getRulesetVersionId()).map(RulesetVersion::getVersion).orElse(null);
        String analysisVersion = check.getAnalysisVersion() == null ? rulesetVersion : check.getAnalysisVersion();
        return new CheckResult(check.getId(), check.getStatus(), check.getMarketplaceCode(), rulesetVersion, analysisVersion,
                check.getContentHash(), cached, check.getCreatedAt(), CheckResult.Summary.of(views), views);
    }
}
