package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
     * Findings keep rule registration order.
     */
    public List<Finding> run(Listing listing, RulesetConfig config) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : registry.rulesFor(config.marketplace())) {
            try {
                findings.addAll(rule.evaluate(listing, config));
            } catch (RuntimeException e) {
                log.error("Rule '{}' failed on ruleset {}; skipping it", rule.id(), config.version(), e);
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Fails fast if the ruleset does not match the registered rules: a rule without configuration,
     * a configured rule without a class (usually a typo), or a missing or malformed parameter.
     */
    public void validate(RulesetConfig config) {
        List<Rule> rules = registry.rulesFor(config.marketplace());
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
