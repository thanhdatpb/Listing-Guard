package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.Analysis;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.PartState;
import com.thanhdatpb.listingguard.api.evaluation.Rate;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvalCase.Expected;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Scores the checks against the labelled sample set (ADR-032): per rule, the true positives, false positives and false
 * negatives over every case, and from them precision and recall.
 *
 * <p>A reported finding is identified by its rule, its field and, for a trademark finding, the catalog mark (normalized);
 * repeats of the same identity count once. An expected entry matches a reported finding of its rule whose field and mark equal
 * the ones it pins (an entry that pins neither matches any finding of the rule). Each expected entry is one true positive or
 * one false negative of its rule; each reported finding no entry matches is one false positive of its rule.
 *
 * <p>A listing rule is keyed {@code <MARKETPLACE>/<rule id>} (the Etsy and the Amazon {@code title_length} are different
 * rules with different sources), a trademark rule {@code trademark/<rule id>} for both marketplaces. A case in which a rule
 * failed, or whose trademark scan is not complete, is a problem of the set: it is reported and the gate fails on it.
 */
public final class ListingEvaluator {

    public static final String FORMAT = "listing-guard/eval-listing/v1";

    /** What one case gave. */
    public record CaseResult(String id, String marketplace, List<String> truePositives, List<String> falseNegatives,
                             List<String> falsePositives, String expectedTrademarkRisk, String trademarkRisk,
                             String problem) {

        boolean passed() {
            return falseNegatives.isEmpty() && falsePositives.isEmpty() && problem == null
                    && (expectedTrademarkRisk == null || expectedTrademarkRisk.equals(trademarkRisk));
        }
    }

    /** The score of one rule over the set. */
    public record RuleScore(String key, String sourceStatus, int truePositives, int falsePositives, int falseNegatives,
                            Rate precision, Rate recall) {
    }

    /** The score of the whole set. */
    public record Result(String format, Map<String, String> rulesetVersions, String trademarkRulesetVersion,
                         String trademarkCatalogVersion, int cases, int casesPassed, Rate trademarkRiskAgreement,
                         List<RuleScore> rules, List<String> verifiedRulesWithoutCase, List<CaseResult> caseResults) {

        /** The problems of the set: a case whose analysis was not complete. */
        public List<String> problems() {
            return caseResults.stream().filter(result -> result.problem() != null)
                    .map(result -> result.id() + ": " + result.problem()).toList();
        }
    }

    private record Reported(String rule, String field, String mark) {

        String label() {
            return rule + " @ " + field + (mark == null ? "" : " [" + mark + "]");
        }
    }

    private final ListingAnalyzer analyzer;
    private final Function<String, RulesetConfig> rulesets;
    private final TrademarkScanner scanner;
    private final TextNormalizer markNormalizer;

    /**
     * @param analyzer the analyzer, with the active rulesets and the DEMO catalog scanner ({@link DemoCatalogScanner})
     * @param rulesets the same rulesets the analyzer runs, by marketplace code
     * @param scanner  the same scanner the analyzer runs
     */
    public ListingEvaluator(ListingAnalyzer analyzer, Function<String, RulesetConfig> rulesets, TrademarkScanner scanner) {
        this.analyzer = analyzer;
        this.rulesets = rulesets;
        this.scanner = scanner;
        this.markNormalizer = new TextNormalizer(scanner.ruleset().normalization());
    }

    /**
     * @throws IllegalArgumentException when a case expects a rule that the active ruleset of its marketplace does not have,
     *                                  or a trademark rule that does not exist: such a case could never pass
     */
    public Result evaluate(List<ListingEvalCase> cases) {
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, String> statuses = new TreeMap<>();
        Map<String, String> versions = new TreeMap<>();
        List<CaseResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int riskExpected = 0;
        int riskAgreed = 0;
        for (ListingEvalCase sample : cases) {
            if (!seen.add(sample.id())) {
                throw new IllegalArgumentException("Two sample cases have the id " + sample.id() + ".");
            }
            RulesetConfig ruleset = rulesets.apply(sample.marketplace());
            versions.put(sample.marketplace(), ruleset.version());
            ruleset.rules().forEach((id, settings) -> statuses.put(sample.marketplace() + "/" + id,
                    settings.source().status().name()));
            for (Expected expected : sample.expected()) {
                validate(sample, expected, ruleset);
            }
            CaseResult result = score(sample, counts);
            results.add(result);
            if (result.expectedTrademarkRisk() != null) {
                riskExpected++;
                if (result.expectedTrademarkRisk().equals(result.trademarkRisk())) {
                    riskAgreed++;
                }
            }
        }
        RuleIds.ALL.forEach(id -> statuses.put(ListingEvalCase.TRADEMARK_PREFIX + id, "UNVERIFIED"));

        List<RuleScore> scores = new ArrayList<>();
        counts.forEach((key, c) -> scores.add(new RuleScore(key, statuses.getOrDefault(key, "UNVERIFIED"), c[0], c[1], c[2],
                new Rate(c[0], c[0] + c[1]), new Rate(c[0], c[0] + c[2]))));
        List<String> withoutCase = new ArrayList<>();
        statuses.forEach((key, status) -> {
            if (status.equals("VERIFIED") && !counts.containsKey(key)) {
                withoutCase.add(key);
            }
        });
        int passed = (int) results.stream().filter(CaseResult::passed).count();
        return new Result(FORMAT, versions, scanner.ruleset().version(), scanner.catalog().version(), cases.size(), passed,
                new Rate(riskAgreed, riskExpected), List.copyOf(scores), List.copyOf(withoutCase), List.copyOf(results));
    }

    private void validate(ListingEvalCase sample, Expected expected, RulesetConfig ruleset) {
        String rule = expected.rule();
        if (rule.startsWith(ListingEvalCase.TRADEMARK_PREFIX)) {
            if (!RuleIds.ALL.contains(rule.substring(ListingEvalCase.TRADEMARK_PREFIX.length()))) {
                throw new IllegalArgumentException("Sample case " + sample.id() + " expects " + rule
                        + ", which is not a trademark rule (" + RuleIds.ALL + ").");
            }
        } else if (!ruleset.rules().containsKey(rule)) {
            throw new IllegalArgumentException("Sample case " + sample.id() + " expects " + rule + ", which the active "
                    + sample.marketplace() + " ruleset " + ruleset.version() + " does not have.");
        }
    }

    private CaseResult score(ListingEvalCase sample, Map<String, int[]> counts) {
        Analysis analysis = analyzer.analyze(sample.marketplace(), sample.listing());
        String problem = null;
        Set<Reported> reported = new LinkedHashSet<>();
        if (analysis.rulesState() != PartState.DONE) {
            problem = "rule check " + analysis.rulesState() + ": " + analysis.rulesMessage();
        } else {
            if (!analysis.rules().complete()) {
                problem = "rules failed: " + analysis.rules().failedRuleIds();
            }
            for (Finding finding : analysis.rules().findings()) {
                reported.add(new Reported(finding.ruleId(), finding.field(), null));
            }
        }
        String risk = null;
        if (analysis.trademarkState() != PartState.DONE) {
            problem = join(problem, "trademark scan " + analysis.trademarkState() + ": " + analysis.trademarkMessage());
        } else {
            risk = analysis.trademark().riskLevel().name();
            if (analysis.trademark().completeness() != Completeness.COMPLETE) {
                problem = join(problem, "trademark rules failed: " + analysis.trademark().failedRuleIds());
            }
            for (TrademarkFinding finding : analysis.trademark().findings()) {
                String mark = finding.evidence() == null || finding.evidence().matchedMark() == null ? null
                        : markNormalizer.normalizeMark(finding.evidence().matchedMark());
                reported.add(new Reported(ListingEvalCase.TRADEMARK_PREFIX + finding.ruleId(),
                        finding.evidence() == null ? null : finding.evidence().field(), mark));
            }
        }

        List<String> truePositives = new ArrayList<>();
        List<String> falseNegatives = new ArrayList<>();
        Set<Reported> matched = new LinkedHashSet<>();
        for (Expected expected : sample.expected()) {
            String expectedMark = expected.mark() == null ? null : markNormalizer.normalizeMark(expected.mark());
            List<Reported> hits = reported.stream().filter(item -> item.rule().equals(expected.rule())
                    && (expected.field() == null || expected.field().equals(item.field()))
                    && (expectedMark == null || expectedMark.equals(item.mark()))).toList();
            String key = key(sample.marketplace(), expected.rule());
            String label = expected.rule() + (expected.field() == null ? "" : " @ " + expected.field())
                    + (expected.mark() == null ? "" : " [" + expected.mark() + "]");
            if (hits.isEmpty()) {
                counts.computeIfAbsent(key, k -> new int[3])[2]++;
                falseNegatives.add(label);
            } else {
                counts.computeIfAbsent(key, k -> new int[3])[0]++;
                truePositives.add(label);
                matched.addAll(hits);
            }
        }
        List<String> falsePositives = new ArrayList<>();
        for (Reported item : reported) {
            if (!matched.contains(item)) {
                counts.computeIfAbsent(key(sample.marketplace(), item.rule()), k -> new int[3])[1]++;
                falsePositives.add(item.label());
            }
        }
        RiskLevel expectedRisk = sample.trademarkRisk();
        return new CaseResult(sample.id(), sample.marketplace(), List.copyOf(truePositives), List.copyOf(falseNegatives),
                List.copyOf(falsePositives), expectedRisk == null ? null : expectedRisk.name(), risk, problem);
    }

    private static String key(String marketplace, String rule) {
        return rule.startsWith(ListingEvalCase.TRADEMARK_PREFIX) ? rule : marketplace + "/" + rule;
    }

    private static String join(String first, String second) {
        return first == null ? second : first + "; " + second;
    }
}
