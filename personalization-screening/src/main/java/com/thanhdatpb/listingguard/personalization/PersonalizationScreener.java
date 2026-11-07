package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.UnknownPrintProfileException;
import com.thanhdatpb.listingguard.personalization.rules.RuleContext;
import com.thanhdatpb.listingguard.personalization.rules.TextRule;
import com.thanhdatpb.listingguard.personalization.rules.TextRules;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screens the text a buyer typed for a custom product (ADR-016): the character rules of the ruleset on every value, and
 * the trademark scanner (ADR-009) when a catalog is served. Deterministic and offline: it reads the ruleset, the input
 * and the scanner it is given and nothing else. It never changes a value.
 *
 * <p>A character rule that throws does not stop the others: it is reported in {@code failedRuleIds}, the result is
 * {@code INCOMPLETE} and the findings of the other rules are kept (ADR-010). A scan without a scanner (no catalog) leaves the
 * trademark part {@code NOT_CONFIGURED} with an {@code UNKNOWN} risk and the result {@code INCOMPLETE}. Log lines never
 * contain a value.
 *
 * <p>A screener is immutable and safe to share.
 */
public final class PersonalizationScreener {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationScreener.class);

    private final PersonalizationRuleset ruleset;
    private final List<TextRule> rules;

    public PersonalizationScreener(PersonalizationRuleset ruleset) {
        this(ruleset, TextRules.create(ruleset));
    }

    /**
     * A screener that runs these rules instead of the ruleset's own, in this order. For tests that need a rule to fail (the
     * result is then {@code INCOMPLETE}).
     */
    public PersonalizationScreener(PersonalizationRuleset ruleset, List<TextRule> rules) {
        this.ruleset = ruleset;
        this.rules = List.copyOf(rules);
    }

    public PersonalizationRuleset ruleset() {
        return ruleset;
    }

    /**
     * The fingerprint of this screener's ruleset and algorithms for a marketplace.
     *
     * @param trademarkFingerprint the value of the trademark scanner's fingerprint, or null when no catalog is served
     */
    public AnalysisFingerprint fingerprint(String marketplace, String trademarkFingerprint) {
        return AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, marketplace, trademarkFingerprint);
    }

    /**
     * @param scanner the trademark scanner on the catalog the application serves, or null when none is served
     * @throws UnknownPrintProfileException if the input names a print profile the ruleset does not declare
     */
    public ScreeningResult screen(PersonalizationInput input, TrademarkScanner scanner) {
        PersonalizationRuleset.PrintProfile profile = ruleset.profile(input.printProfile());
        String hash = CanonicalInput.of(input).hash();
        RuleContext context = new RuleContext(ruleset, profile, input.values());

        Set<String> failed = new LinkedHashSet<>();
        List<TextFinding> findings = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        for (TextRule rule : rules) {
            order.put(rule.id(), order.size());
            try {
                findings.addAll(rule.apply(context));
            } catch (RuntimeException e) {
                log.error("Personalization rule '{}' failed on ruleset {}; keeping the other rules' findings", rule.id(),
                        ruleset.version(), e);
                failed.add(rule.id());
            }
        }
        findings.sort(Comparator.comparingInt(TextFinding::valueIndex)
                .thenComparingInt(finding -> order.getOrDefault(finding.ruleId(), Integer.MAX_VALUE))
                .thenComparingInt(TextFinding::start));

        TrademarkPart trademark;
        if (scanner == null) {
            trademark = TrademarkPart.notConfigured();
        } else {
            try {
                trademark = TrademarkAdapter.scan(scanner, context);
            } catch (RuntimeException e) {
                log.error("The trademark part of a personalization screening failed on ruleset {}", ruleset.version(), e);
                trademark = new TrademarkPart(TrademarkStatus.INCOMPLETE, RiskLevel.UNKNOWN, null, null,
                        List.of("trademark_scan"), List.of());
            }
        }
        Completeness completeness = failed.isEmpty() && trademark.status() == TrademarkStatus.COMPLETE
                ? Completeness.COMPLETE : Completeness.INCOMPLETE;
        return new ScreeningResult(hash, ruleset.version(), profile.code(), completeness, findings, List.copyOf(failed),
                trademark, ruleset.disclaimer());
    }
}
