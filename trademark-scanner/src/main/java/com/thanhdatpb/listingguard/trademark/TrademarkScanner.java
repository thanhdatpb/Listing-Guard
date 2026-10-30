package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.Templates;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.rules.Candidate;
import com.thanhdatpb.listingguard.trademark.rules.Precedence;
import com.thanhdatpb.listingguard.trademark.rules.ScanContext;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRule;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRules;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a listing's text for possible trademark references (ADR-009). Deterministic and offline: it reads the
 * ruleset and one {@link TrademarkCatalog} version and nothing else. The result is a reference warning, never a legal
 * conclusion.
 *
 * <p>A rule that throws does not stop the others. It is reported in {@code failedRuleIds}, the result is
 * {@code INCOMPLETE}, the findings of the rules that succeeded are kept, and the risk level is at least the highest
 * established one or {@code UNKNOWN}, never {@code LOW}. A rule that reads the results of another rule (the color
 * rules read the catalog rules) is reported as failed too when that other rule failed, because its result would no
 * longer be trustworthy.
 *
 * <p>A scanner is immutable and safe to share; the catalog it is built with must not change version.
 */
public final class TrademarkScanner {

    private static final Logger log = LoggerFactory.getLogger(TrademarkScanner.class);

    private final TrademarkRuleset ruleset;
    private final TrademarkCatalog catalog;
    private final String catalogVersion;
    private final TextNormalizer normalizer;
    private final List<TrademarkRule> rules;

    public TrademarkScanner(TrademarkRuleset ruleset, TrademarkCatalog catalog) {
        this(ruleset, catalog, TrademarkRules.create(ruleset));
    }

    /** For tests that need a rule to fail. */
    TrademarkScanner(TrademarkRuleset ruleset, TrademarkCatalog catalog, List<TrademarkRule> rules) {
        this.ruleset = ruleset;
        this.catalog = catalog;
        this.catalogVersion = catalog.version();
        this.normalizer = new TextNormalizer(ruleset.normalization());
        this.rules = List.copyOf(rules);
    }

    public TrademarkRuleset ruleset() {
        return ruleset;
    }

    /** The fingerprint of this scanner's ruleset and algorithms plus the components only the caller knows. */
    public AnalysisFingerprint fingerprint(Map<String, String> externalComponents) {
        return AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, externalComponents);
    }

    public TrademarkResult scan(TrademarkCheckInput input) {
        CanonicalInput canonical = CanonicalInput.of(input);
        Set<String> failed = new LinkedHashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> fieldOrder = new ArrayList<>();
        ScanContext context = null;
        try {
            context = ScanContext.create(ruleset, catalog, normalizer, canonical);
            context.fields().forEach(field -> fieldOrder.add(field.name()));
        } catch (RuntimeException e) {
            log.error("Trademark scan could not normalize the input; every rule is reported as failed", e);
            rules.forEach(rule -> failed.add(rule.id()));
        }

        if (context != null) {
            for (TrademarkRule rule : rules) {
                List<String> needed = RuleIds.REQUIRES.getOrDefault(rule.id(), List.of());
                if (needed.stream().anyMatch(failed::contains)) {
                    log.warn("Trademark rule '{}' is reported as failed because a rule it reads failed", rule.id());
                    failed.add(rule.id());
                    continue;
                }
                try {
                    candidates = rule.apply(context, candidates);
                } catch (RuntimeException e) {
                    log.error("Trademark rule '{}' failed on ruleset {}; keeping the other rules' findings",
                            rule.id(), ruleset.version(), e);
                    failed.add(rule.id());
                }
            }
        }

        List<TrademarkFinding> findings = new ArrayList<>();
        if (context != null) {
            for (Candidate candidate : Precedence.resolve(ruleset, candidates, fieldOrder)) {
                findings.add(finding(context, candidate));
            }
        }
        Completeness completeness = failed.isEmpty() ? Completeness.COMPLETE : Completeness.INCOMPLETE;
        return new TrademarkResult(canonical.hash(), ruleset.version(), catalogVersion,
                riskLevel(findings, completeness), completeness, findings, List.copyOf(failed), ruleset.disclaimer());
    }

    private TrademarkFinding finding(ScanContext context, Candidate candidate) {
        NormalizedField field = context.field(candidate.field());
        String message = Templates.fill(messageTemplate(candidate.ruleId(), true), candidate.placeholders());
        if (candidate.insideColorPhrase()) {
            message = message + " " + ruleset.colorContainsMark().note();
        }
        String suggestion = Templates.fill(messageTemplate(candidate.ruleId(), false), candidate.placeholders());
        Evidence evidence = new Evidence(candidate.field(), candidate.start(), candidate.end(),
                context.snippet(field, candidate.start(), candidate.end()), candidate.normalizedToken(),
                candidate.mark(), candidate.type(), candidate.score(), catalogVersion, candidate.ruleId(),
                candidate.insideColorPhrase(), candidate.otherRuleIds());
        return new TrademarkFinding(candidate.ruleId(), candidate.risk(), message, suggestion, evidence);
    }

    private String messageTemplate(String ruleId, boolean message) {
        return switch (ruleId) {
            case RuleIds.GEO_SPORT_COMBINATION ->
                    message ? ruleset.geoSport().message() : ruleset.geoSport().suggestion();
            case RuleIds.PURE_COLOR_DESCRIPTION ->
                    message ? ruleset.pureColor().message() : ruleset.pureColor().suggestion();
            default -> message ? ruleset.catalogRule(ruleId).message() : ruleset.catalogRule(ruleId).suggestion();
        };
    }

    /**
     * The highest finding level, LOW for a complete scan with no findings. When the scan is incomplete the level is
     * kept only if it is MEDIUM or HIGH; otherwise the risk is not determined and the level is UNKNOWN.
     */
    static RiskLevel riskLevel(List<TrademarkFinding> findings, Completeness completeness) {
        RiskLevel highest = RiskLevel.highest(findings.stream().map(TrademarkFinding::risk).toList());
        if (completeness == Completeness.COMPLETE) {
            return highest == null ? RiskLevel.LOW : highest;
        }
        return highest != null && highest != RiskLevel.LOW ? highest : RiskLevel.UNKNOWN;
    }
}
