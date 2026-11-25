package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.Templates;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.rules.Candidate;
import com.thanhdatpb.listingguard.trademark.rules.CatalogCalibration;
import com.thanhdatpb.listingguard.trademark.rules.Precedence;
import com.thanhdatpb.listingguard.trademark.rules.ScanContext;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRule;
import com.thanhdatpb.listingguard.trademark.rules.TrademarkRules;
import com.thanhdatpb.listingguard.trademark.rules.WatchlistMatcher;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistMatch;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
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
 * <p>A client's watchlist (ADR-031) is matched in the same scan ({@link #scan(TrademarkCheckInput, Watchlist)}): its findings
 * have the rule id {@code watchlist_match}, are at least the watchlist's minimum level and win their span. A scanner built
 * {@link #withoutCatalog without a catalog} runs only the rules that need none; the others are reported in
 * {@code skippedRuleIds} and the result is {@code INCOMPLETE}.
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
        this.catalogVersion = catalog == null ? null : catalog.version();
        this.normalizer = new TextNormalizer(ruleset.normalization());
        this.rules = List.copyOf(rules);
    }

    /**
     * A scanner for a deployment that serves no catalog (ADR-031): the catalog rules and the rules that read them do not run
     * and are reported as skipped, so every result is {@code INCOMPLETE} and never {@code LOW}.
     */
    public static TrademarkScanner withoutCatalog(TrademarkRuleset ruleset) {
        return new TrademarkScanner(ruleset, null, TrademarkRules.create(ruleset));
    }

    public TrademarkRuleset ruleset() {
        return ruleset;
    }

    /** Whether this scanner reads a catalog. */
    public boolean hasCatalog() {
        return catalog != null;
    }

    /**
     * The catalog this scanner reads, or null for a scanner {@link #withoutCatalog built without one} (ADR-031). For callers that
     * explain a result after the scan (the backtest of ADR-032 asks it whether a labelled mark is in the catalog at all); asking it
     * never changes what {@link #scan} finds.
     */
    public TrademarkCatalog catalog() {
        return catalog;
    }

    /** The fingerprint of this scanner's ruleset and algorithms plus the components only the caller knows. */
    public AnalysisFingerprint fingerprint(Map<String, String> externalComponents) {
        return AnalysisFingerprint.of(ruleset, AlgorithmVersions.CURRENT, externalComponents);
    }

    /**
     * @throws com.thanhdatpb.listingguard.trademark.config.UnknownProductTypeException if the input names a product type
     *         the ruleset does not declare. This is the caller's mistake and is raised before any work: the scan is
     *         never run without the product type it was asked for, and it is never reported as {@code INCOMPLETE}.
     */
    public TrademarkResult scan(TrademarkCheckInput input) {
        return scan(input, Watchlist.EMPTY);
    }

    /**
     * The scan with a client's watchlist. With {@link Watchlist#EMPTY} it is exactly {@link #scan(TrademarkCheckInput)}.
     *
     * @throws com.thanhdatpb.listingguard.trademark.config.UnknownProductTypeException as {@link #scan(TrademarkCheckInput)}
     */
    public TrademarkResult scan(TrademarkCheckInput input, Watchlist watchlist) {
        CanonicalInput canonical = CanonicalInput.of(input);
        String productType = canonical.productType();
        Set<Integer> productClasses = productType == null ? null : ruleset.productClasses().classesOf(productType);
        Set<String> failed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> fieldOrder = new ArrayList<>();
        ScanContext context = null;
        try {
            context = ScanContext.create(ruleset, catalog, normalizer, canonical, productType, productClasses);
            context.fields().forEach(field -> fieldOrder.add(field.name()));
        } catch (RuntimeException e) {
            log.error("Trademark scan could not normalize the input; every rule is reported as failed", e);
            rules.forEach(rule -> failed.add(rule.id()));
            if (!watchlist.isEmpty()) {
                failed.add(RuleIds.WATCHLIST_MATCH);
            }
        }

        if (context != null) {
            for (TrademarkRule rule : rules) {
                List<String> needed = RuleIds.REQUIRES.getOrDefault(rule.id(), List.of());
                if (catalog == null && (RuleIds.CATALOG.contains(rule.id()) || needed.stream().anyMatch(skipped::contains))) {
                    // no catalog is served: the rule cannot run, which is not a failure but leaves the result incomplete
                    skipped.add(rule.id());
                    continue;
                }
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
            if (!watchlist.isEmpty()) {
                try {
                    candidates.addAll(WatchlistMatcher.candidates(context, watchlist, normalizer));
                    candidates = WatchlistMatcher.raise(candidates);
                } catch (RuntimeException e) {
                    log.error("The watchlist rule failed on ruleset {}; keeping the other rules' findings", ruleset.version(), e);
                    failed.add(RuleIds.WATCHLIST_MATCH);
                    candidates = candidates.stream().filter(c -> !RuleIds.WATCHLIST_MATCH.equals(c.ruleId())).toList();
                }
            }
        }

        List<TrademarkFinding> findings = new ArrayList<>();
        if (context != null) {
            CatalogCalibration calibration = ruleset.calibration() == null ? null : new CatalogCalibration(context);
            if (calibration != null && catalog != null && !catalog.mayHaveDistinctiveness()) {
                skipped.add(CatalogCalibration.SKIPPED_ID);
            }
            for (Candidate candidate : Precedence.resolve(ruleset, candidates, fieldOrder)) {
                if (calibration != null) {
                    if (CatalogCalibration.missingTier(candidate)) {
                        skipped.add(CatalogCalibration.SKIPPED_ID);
                    }
                    candidate = calibration.apply(candidate);
                }
                findings.add(finding(context, candidate, watchlist.settings()));
            }
        }
        Completeness completeness = failed.isEmpty() && skipped.isEmpty() ? Completeness.COMPLETE : Completeness.INCOMPLETE;
        return new TrademarkResult(canonical.hash(), ruleset.version(), catalogVersion,
                riskLevel(findings, completeness), completeness, findings, List.copyOf(failed), ruleset.disclaimer(),
                List.copyOf(skipped), ruleset.calibration() != null);
    }

    private TrademarkFinding finding(ScanContext context, Candidate candidate, WatchlistSettings settings) {
        NormalizedField field = context.field(candidate.field());
        if (candidate.watchlist() != null) {
            return watchlistFinding(context, field, candidate, settings);
        }
        String message = Templates.fill(messageTemplate(candidate.ruleId(), true), candidate.placeholders());
        if (candidate.insideColorPhrase()) {
            message = message + " " + ruleset.colorContainsMark().note();
        }
        // a lowered level always says why, with the level the rule assigned
        for (RiskAdjustment adjustment : candidate.riskAdjustments()) {
            message = message + " " + adjustment.note();
        }
        String suggestion = Templates.fill(messageTemplate(candidate.ruleId(), false), candidate.placeholders());
        Evidence evidence = new Evidence(candidate.field(), candidate.start(), candidate.end(),
                context.snippet(field, candidate.start(), candidate.end()), candidate.normalizedToken(),
                candidate.mark(), candidate.type(), candidate.score(), catalogVersion, candidate.ruleId(),
                candidate.insideColorPhrase(), candidate.otherRuleIds(), candidate.sourceIds(),
                candidate.riskAdjustments(), ruleset.sourceLinks().resolve(candidate.sourceIds()));
        return new TrademarkFinding(candidate.ruleId(), candidate.risk(), message, suggestion, evidence);
    }

    /** A finding of the client's watchlist: its message names the entry, the alias that matched and the entry's note. */
    private TrademarkFinding watchlistFinding(ScanContext context, NormalizedField field, Candidate candidate,
                                              WatchlistSettings settings) {
        WatchlistMatch match = candidate.watchlist();
        String message = Templates.fill(settings.message(), candidate.placeholders());
        if (match.matchedAlias() != null) {
            message = message + " " + Templates.fill(settings.aliasNote(), candidate.placeholders());
        }
        if (match.note() != null && !match.note().isBlank()) {
            message = message + " " + Templates.fill(settings.reasonNote(), candidate.placeholders());
        }
        String suggestion = Templates.fill(settings.suggestion(), candidate.placeholders());
        Evidence evidence = new Evidence(candidate.field(), candidate.start(), candidate.end(),
                context.snippet(field, candidate.start(), candidate.end()), candidate.normalizedToken(),
                candidate.mark(), candidate.type(), candidate.score(), catalogVersion, candidate.ruleId(),
                candidate.insideColorPhrase(), candidate.otherRuleIds(), candidate.sourceIds(), List.of(),
                ruleset.sourceLinks().resolve(candidate.sourceIds()), match);
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
