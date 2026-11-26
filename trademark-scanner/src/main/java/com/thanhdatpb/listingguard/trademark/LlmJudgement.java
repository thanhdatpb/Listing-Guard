package com.thanhdatpb.listingguard.trademark;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the verdicts of a language model to a scan result (ADR-036). It holds no model and makes no request: it only decides what
 * a verdict is allowed to change, and that is very little.
 *
 * <ul>
 *   <li>Only a finding of the catalog at level MEDIUM is eligible ({@link #eligible}): never a HIGH, never a LOW (already the
 *       informational floor), never a watchlist or context finding.</li>
 *   <li>Only {@link Verdict#LIKELY_DESCRIPTIVE} changes anything: the finding is kept, goes from MEDIUM to LOW and gets a risk
 *       adjustment with reason {@code llm_descriptive} and the model's reason. Any other verdict changes nothing.</li>
 *   <li>A finding is never removed and a level is never raised. The overall level is recomputed from the findings, so it can only
 *       go down.</li>
 *   <li>A step that could not run for an eligible finding (no provider had quota, all failed, none was allowed to see the text) is
 *       recorded as skipped: the result is INCOMPLETE and lists {@link #SKIPPED_ID}, the deterministic findings stay as they were.</li>
 * </ul>
 */
public final class LlmJudgement {

    /** The id recorded in {@code skippedRuleIds} when the model step could not run for an eligible finding. */
    public static final String SKIPPED_ID = "llm_context_judge";

    /** The label of a demoted finding; the model's reason follows. */
    public static final String LABEL = "LLM: có thể là từ mô tả.";

    public enum Verdict {
        LIKELY_MARK_USE,
        LIKELY_DESCRIPTIVE,
        UNSURE
    }

    /**
     * One verdict. The reason is only used with LIKELY_DESCRIPTIVE.
     *
     * @param basis what the model was given with the sentence (the evidence pack of ADR-051), or null when it got none: then a
     *              demotion is recorded exactly as before. Only a demotion keeps it; any other verdict changes nothing.
     */
    public record Judged(Verdict verdict, String reason, JudgeBasis basis) {

        public static final Judged UNSURE = new Judged(Verdict.UNSURE, "");

        public Judged(Verdict verdict, String reason) {
            this(verdict, reason, null);
        }
    }

    private LlmJudgement() {
    }

    /** True for a finding the judge may look at: a catalog finding at level MEDIUM. */
    public static boolean eligible(TrademarkFinding finding) {
        return finding.source() == FindingSource.CATALOG && finding.risk() == RiskLevel.MEDIUM;
    }

    private static boolean eligible(TrademarkResult result, TrademarkFinding finding) {
        return eligible(finding) && (!result.restrictLlmToOrdinary() || finding.evidence().riskAdjustments().stream()
                .anyMatch(adjustment -> adjustment.reason() == AdjustmentReason.CATALOG_ORDINARY_EXACT));
    }

    /** The positions of the eligible findings, in the order of the result. */
    public static List<Integer> eligibleIndexes(TrademarkResult result) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < result.findings().size(); i++) {
            if (eligible(result, result.findings().get(i))) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    /**
     * @param verdicts the verdict per finding position; a position without one was not judged
     * @param skipped  true when the model step could not run for at least one eligible finding
     * @return the same result when nothing changes, otherwise a result with lowered findings and, when skipped, INCOMPLETE
     */
    public static TrademarkResult apply(TrademarkResult result, Map<Integer, Judged> verdicts, boolean skipped) {
        List<TrademarkFinding> findings = new ArrayList<>(result.findings());
        boolean changed = false;
        for (Map.Entry<Integer, Judged> entry : verdicts.entrySet()) {
            int index = entry.getKey();
            Judged judged = entry.getValue();
            if (index < 0 || index >= findings.size() || judged == null || judged.verdict() != Verdict.LIKELY_DESCRIPTIVE) {
                continue;
            }
            TrademarkFinding finding = findings.get(index);
            if (!eligible(result, finding)) {
                continue;
            }
            findings.set(index, lowered(finding, judged));
            changed = true;
        }
        if (!changed && !skipped) {
            return result;
        }
        Completeness completeness = skipped ? Completeness.INCOMPLETE : result.completeness();
        Set<String> skippedIds = new LinkedHashSet<>(result.skippedRuleIds());
        if (skipped) {
            skippedIds.add(SKIPPED_ID);
        }
        RiskLevel level = TrademarkScanner.riskLevel(findings, completeness);
        return new TrademarkResult(result.inputHash(), result.rulesetVersion(), result.catalogVersion(), level, completeness,
                findings, result.failedRuleIds(), result.disclaimer(), List.copyOf(skippedIds));
    }

    private static TrademarkFinding lowered(TrademarkFinding finding, Judged judged) {
        Evidence old = finding.evidence();
        String reason = judged.reason();
        // every adjustment of a finding states the level the rule assigned, so the new one does too
        RiskLevel from = old.riskAdjustments().isEmpty() ? finding.risk() : old.riskAdjustments().get(0).from();
        String note = reason == null || reason.isBlank() ? LABEL : LABEL + " " + reason.strip();
        List<RiskAdjustment> adjustments = new ArrayList<>(old.riskAdjustments());
        // the evidence the model was given and its checked reason are kept with the demotion; with no evidence pack the adjustment
        // is exactly the one ADR-036 describes
        JudgeBasis basis = judged.basis() == null ? null
                : judged.basis().withReason(reason == null || reason.isBlank() ? null : reason.strip());
        adjustments.add(new RiskAdjustment(AdjustmentReason.LLM_DESCRIPTIVE, from, RiskLevel.LOW, note, null, basis));
        Evidence evidence = new Evidence(old.field(), old.start(), old.end(), old.snippet(), old.normalizedToken(),
                old.matchedMark(), old.matchType(), old.score(), old.catalogVersion(), old.ruleId(), old.insideColorPhrase(),
                old.otherRuleIds(), old.sourceIds(), adjustments, old.sourceLinks(), old.watchlist(), old.ownerCountSameMark());
        return new TrademarkFinding(finding.ruleId(), RiskLevel.LOW, finding.message(), finding.suggestion(), evidence);
    }
}
