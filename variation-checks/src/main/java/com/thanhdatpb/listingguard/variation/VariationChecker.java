package com.thanhdatpb.listingguard.variation;

import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.rules.RuleContext;
import com.thanhdatpb.listingguard.variation.rules.VariationRule;
import com.thanhdatpb.listingguard.variation.rules.VariationRules;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks a variation family against a {@link VariationRuleset} (ADR-015). Deterministic and offline: it reads the
 * ruleset and the input and nothing else.
 *
 * <p>A rule that throws does not stop the others. It is reported in {@code failedRuleIds}, the result is
 * {@code INCOMPLETE} and the findings of the rules that succeeded are kept (ADR-010). Rules run in the order the
 * ruleset declares, and only those declared for the input's marketplace.
 *
 * <p>A checker is immutable and safe to share.
 */
public final class VariationChecker {

    private static final Logger log = LoggerFactory.getLogger(VariationChecker.class);

    private final VariationRuleset ruleset;
    private final List<VariationRule> rules;

    public VariationChecker(VariationRuleset ruleset) {
        this(ruleset, VariationRules.create(ruleset));
    }

    /** For tests that need a rule to fail. */
    VariationChecker(VariationRuleset ruleset, List<VariationRule> rules) {
        this.ruleset = ruleset;
        this.rules = List.copyOf(rules);
    }

    public VariationRuleset ruleset() {
        return ruleset;
    }

    /** The fingerprint of this checker's ruleset and algorithms for a marketplace. */
    public AnalysisFingerprint fingerprint(String marketplace) {
        return AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, marketplace);
    }

    /** @throws IllegalArgumentException if the ruleset has no rule for the input's marketplace */
    public VariationResult check(VariationInput input) {
        String marketplace = input.marketplace();
        if (!ruleset.marketplaces().contains(marketplace)) {
            throw new IllegalArgumentException("Ruleset " + ruleset.version() + " has no rule for marketplace " + marketplace);
        }
        String hash = CanonicalInput.of(input).hash();
        List<VariationRule> applicable = rules.stream().filter(rule -> rule.marketplaces().contains(marketplace)).toList();
        Set<String> failed = new LinkedHashSet<>();
        List<VariationFinding> findings = new ArrayList<>();

        RuleContext context = null;
        try {
            context = RuleContext.of(input, ruleset);
        } catch (RuntimeException e) {
            log.error("Variation check could not prepare the input; every rule is reported as failed", e);
            applicable.forEach(rule -> failed.add(rule.id()));
        }
        if (context != null) {
            for (VariationRule rule : applicable) {
                try {
                    findings.addAll(rule.apply(context));
                } catch (RuntimeException e) {
                    log.error("Variation rule '{}' failed on ruleset {}; keeping the other rules' findings", rule.id(),
                            ruleset.version(), e);
                    failed.add(rule.id());
                }
            }
        }
        Completeness completeness = failed.isEmpty() ? Completeness.COMPLETE : Completeness.INCOMPLETE;
        return new VariationResult(hash, ruleset.version(), completeness, findings, List.copyOf(failed),
                ruleset.disclaimer());
    }
}
