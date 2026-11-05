package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs every rule of a marketplace against a listing and collects the findings. */
public final class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRegistry registry;

    public RuleEngine(RuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * Never stops on a failing rule: a rule that throws is logged and skipped, the others still run.
     * A rule switched off with {@code enabled: false} (for the listing's product type) does not run.
     * Findings keep rule registration order. A fix a finding carries has been verified (see {@link #runDetailed}).
     */
    public List<Finding> run(Listing listing, RulesetConfig config) {
        return runDetailed(listing, config).findings();
    }

    /**
     * As {@link #run}, and also says which rules failed, so a consumer can tell a clean listing from an incomplete run.
     *
     * <p>Fixes (ADR-014): a rule only proposes a candidate fix. Here each candidate is applied alone to a copy of the
     * listing and the whole ruleset is run again on the copy; the fix is dropped (the finding keeps its sentence of
     * advice) when it cannot be applied, when the problem it fixes is still there, when a rule fails that did not fail
     * before, or when the copy has more ERROR or WARNING findings than the original, counted per rule and field (and
     * per value for a tag, because positions move). INFO findings are not compared.
     */
    public RuleRun runDetailed(Listing listing, RulesetConfig config) {
        Evaluation base = evaluate(listing, config);
        List<Finding> findings = new ArrayList<>(base.findings().size());
        Map<String, Integer> baseCounts = null;
        for (Finding finding : base.findings()) {
            if (finding.fix() == null) {
                findings.add(finding);
                continue;
            }
            if (baseCounts == null) {
                baseCounts = problemCounts(base.findings());
            }
            findings.add(fixHolds(finding, listing, config, base, baseCounts) ? finding : finding.withoutFix());
        }
        return new RuleRun(config.packId(), findings, base.failedRuleIds());
    }

    private Evaluation evaluate(Listing listing, RulesetConfig config) {
        List<Finding> findings = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Rule rule : registry.rulesFor(config.packId(), config.marketplace())) {
            try {
                if (!config.rule(rule.id(), listing.productType()).enabled()) {
                    continue;
                }
                findings.addAll(rule.evaluate(listing, config));
            } catch (RuntimeException e) {
                failed.add(rule.id());
                log.error("Rule '{}' failed on ruleset {}; skipping it", rule.id(), config.version(), e);
            }
        }
        return new Evaluation(findings, failed);
    }

    private boolean fixHolds(Finding finding, Listing listing, RulesetConfig config, Evaluation base,
                             Map<String, Integer> baseCounts) {
        Optional<Listing> changed = FixApplier.apply(listing, finding);
        if (changed.isEmpty()) {
            return false;
        }
        Evaluation after = evaluate(changed.get(), config);
        if (!after.failedRuleIds().equals(base.failedRuleIds())) {
            return false;
        }
        Map<String, Integer> afterCounts = problemCounts(after.findings());
        String fixed = problemKey(finding);
        if (afterCounts.getOrDefault(fixed, 0) >= baseCounts.getOrDefault(fixed, 0)) {
            return false;
        }
        for (Finding f : after.findings()) {
            if (f.severity() != Severity.INFO
                    && afterCounts.get(problemKey(f)) > baseCounts.getOrDefault(problemKey(f), 0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> problemCounts(List<Finding> findings) {
        Map<String, Integer> counts = new HashMap<>();
        for (Finding finding : findings) {
            counts.merge(problemKey(finding), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * What a finding is about, independent of its position: rule and field; for an indexed field such as
     * {@code tags[3]} the field name without the index plus the value, because removing a tag moves the others.
     */
    private static String problemKey(Finding finding) {
        String field = finding.field() == null ? "" : finding.field();
        int bracket = field.indexOf('[');
        if (bracket < 0) {
            return finding.ruleId() + "|" + field;
        }
        return finding.ruleId() + "|" + field.substring(0, bracket) + "|" + finding.value();
    }

    private record Evaluation(List<Finding> findings, List<String> failedRuleIds) {
    }

    /**
     * Fails fast if the ruleset does not match the registered rules: a rule without configuration,
     * a configured rule without a class (usually a typo), or a missing or malformed parameter.
     */
    public void validate(RulesetConfig config) {
        List<Rule> rules = registry.rulesFor(config.packId(), config.marketplace());
        Set<String> known = new HashSet<>();
        for (Rule rule : rules) {
            known.add(rule.id());
            rule.validate(config);
        }
        for (String configured : config.rules().keySet()) {
            if (!known.contains(configured)) {
                throw new RulesetException("Ruleset " + config.version() + " configures unknown rule '" + configured + "'");
            }
        }
    }
}
