package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Normalizes the listing, runs the rule engine of its marketplace, and records the check with its
 * findings. There is no LLM step yet, so a check is COMPLETED as soon as the rules have run.
 */
@Service
public class CheckService {

    private final NormalizerRegistry normalizers;
    private final ContentHasher hasher;
    private final RuleEngine ruleEngine;
    private final ActiveRulesets rulesets;
    private final ListingCheckRepository checks;
    private final FindingRepository findings;

    public CheckService(NormalizerRegistry normalizers, ContentHasher hasher, RuleEngine ruleEngine,
                        ActiveRulesets rulesets, ListingCheckRepository checks, FindingRepository findings) {
        this.normalizers = normalizers;
        this.hasher = hasher;
        this.ruleEngine = ruleEngine;
        this.rulesets = rulesets;
        this.checks = checks;
        this.findings = findings;
    }

    @Transactional
    public CheckResponse check(CheckRequest request) {
        var normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        String contentHash = hasher.hash(listing);

        ActiveRulesets.Active ruleset = rulesets.get(marketplace);
        List<Finding> found = ruleEngine.run(listing, ruleset.config());

        ListingCheck saved = checks.save(new ListingCheck(contentHash, marketplace, CheckStatus.COMPLETED));
        findings.saveAll(found.stream()
                .map(finding -> FindingEntity.fromRule(saved.getId(), finding, ruleset.rulesetVersionId()))
                .toList());
        return new CheckResponse(saved.getId(), marketplace, saved.getStatus(), contentHash, found);
    }
}
