package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.Analysis;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.PartState;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the backtest learned about one data row (ADR-032). It holds the row's line and id, its labels, the verdict and the
 * rule ids, and never the listing's text: the one exception is the near word of a {@link MissReason#SPELLING_VARIANT}, cut
 * to a few characters, which is the evidence of that reason.
 *
 * @param status       whether the row was checked, could not be read, or had a label that could not be read
 * @param problem      what was wrong with an unreadable row or its labels (Vietnamese), null otherwise
 * @param verdict      CAUGHT, MISSED or UNDETERMINED for a checked row with a yes/no label; null otherwise
 * @param markMatched  for a row whose claim named a mark: some trademark finding names that mark at the catch level or above
 * @param flaggedRules the rule keys found at the catch line or above (a trademark rule as {@code trademark/<id>})
 * @param foundRules   every rule key found, whatever its severity or level
 * @param evidence     short lines: a rule key, its field and its severity; for a trademark finding the catalog mark
 */
record BacktestRow(int line, String id, Status status, String problem, Boolean claimed, IssueType issue, String mark,
                   Verdict verdict, Set<Caught> caughtBy, boolean markMatched, MissReason missReason, String missDetail,
                   Set<String> flaggedRules, Set<String> foundRules, RiskLevel trademarkRisk, List<String> evidence, BacktestProbe probe) {

    enum Status { CHECKED, UNREADABLE, LABEL_INVALID }

    enum Verdict { CAUGHT, MISSED, UNDETERMINED }

    enum Caught { RULES, TRADEMARK }

    static final String TRADEMARK_PREFIX = "trademark/";
    private static final int EVIDENCE_MAX = 6;

    static BacktestRow unreadable(int line, String id, String problem) {
        return new BacktestRow(line, id, Status.UNREADABLE, problem, null, null, null, null, Set.of(), false, null, null,
                Set.of(), Set.of(), null, List.of(), null);
    }

    static BacktestRow labelInvalid(int line, String id, String problem) {
        return new BacktestRow(line, id, Status.LABEL_INVALID, problem, null, null, null, null, Set.of(), false, null, null,
                Set.of(), Set.of(), null, List.of(), null);
    }

    /**
     * The verdict of a checked row. A row is CAUGHT when a rule finding reaches {@code catchSeverity} or the trademark level
     * reaches {@code catchRisk}; otherwise UNDETERMINED when a part failed, a rule failed or the trademark level is UNKNOWN
     * (it cannot be called a miss), and MISSED else. A trademark catalog that is not served does not make a row
     * undetermined: that holds for the whole run, the report says so, and a trademark claim missed for it says so too.
     */
    static BacktestRow assess(int line, String id, BacktestLabels.Labels labels, Analysis analysis, BacktestOptions options) {
        Set<String> found = new TreeSet<>();
        Set<String> flagged = new TreeSet<>();
        List<String> evidence = new ArrayList<>();
        boolean rulesCaught = false;
        boolean rulesBelow = false;
        boolean rulesDetermined = analysis.rulesState() == PartState.DONE && analysis.rules().complete();
        if (analysis.rulesState() == PartState.DONE) {
            for (Finding finding : analysis.rules().findings()) {
                found.add(finding.ruleId());
                if (atOrAbove(finding.severity(), options.catchSeverity())) {
                    flagged.add(finding.ruleId());
                    rulesCaught = true;
                    addEvidence(evidence, finding.ruleId() + " @ " + finding.field() + " (" + finding.severity() + ")");
                } else {
                    rulesBelow = true;
                }
            }
        }

        RiskLevel risk = null;
        boolean trademarkCaught = false;
        boolean trademarkDetermined = analysis.trademarkState() != PartState.FAILED;
        if (analysis.trademarkState() == PartState.DONE) {
            risk = analysis.trademark().riskLevel();
            trademarkDetermined = risk.isDetermined();
            trademarkCaught = risk.isDetermined() && risk.ordinal() >= options.catchTrademarkRisk().ordinal();
            for (TrademarkFinding finding : analysis.trademark().findings()) {
                String key = TRADEMARK_PREFIX + finding.ruleId();
                found.add(key);
                if (finding.risk().ordinal() >= options.catchTrademarkRisk().ordinal()) {
                    flagged.add(key);
                    String mark = finding.evidence() == null ? null : finding.evidence().matchedMark();
                    addEvidence(evidence, key + " @ " + (finding.evidence() == null ? "?" : finding.evidence().field())
                            + (mark == null ? "" : ": " + mark) + " (" + finding.risk() + ")");
                }
            }
        }

        Set<Caught> caughtBy = EnumSet.noneOf(Caught.class);
        if (rulesCaught) {
            caughtBy.add(Caught.RULES);
        }
        if (trademarkCaught) {
            caughtBy.add(Caught.TRADEMARK);
        }

        String mark = labels.mark();
        boolean markMatched = mark != null && analysis.trademarkState() == PartState.DONE
                && MissDiagnosis.matchedAtLevel(analysis.scanner(), analysis.trademark(), mark, options.catchTrademarkRisk());

        Verdict verdict = null;
        MissReason reason = null;
        String detail = null;
        if (labels.claimed() != null) {
            if (!caughtBy.isEmpty()) {
                verdict = Verdict.CAUGHT;
            } else if (!rulesDetermined || !trademarkDetermined) {
                verdict = Verdict.UNDETERMINED;
            } else {
                verdict = Verdict.MISSED;
            }
        }
        if (verdict == Verdict.MISSED && labels.claimed()) {
            MissDiagnosis.Diagnosis diagnosis = diagnose(labels, analysis, options, rulesBelow);
            reason = diagnosis.reason();
            detail = diagnosis.detail();
        }
        return new BacktestRow(line, id, Status.CHECKED, null, labels.claimed(), labels.issue(), mark, verdict,
                Set.copyOf(caughtBy), markMatched, reason, detail, Set.copyOf(flagged), Set.copyOf(found), risk,
                List.copyOf(evidence), BacktestProbe.assess(line, id, labels, analysis));
    }

    private static MissDiagnosis.Diagnosis diagnose(BacktestLabels.Labels labels, Analysis analysis, BacktestOptions options,
                                                    boolean rulesBelow) {
        IssueType issue = labels.issue();
        if (issue.coverage() == IssueType.Coverage.NONE) {
            return new MissDiagnosis.Diagnosis(MissReason.NOT_COVERED, issue.code());
        }
        boolean trademarkClaim = issue.coverage() == IssueType.Coverage.TRADEMARK
                || (issue.coverage() == IssueType.Coverage.ANY && labels.mark() != null);
        if (trademarkClaim) {
            if (analysis.trademarkState() != PartState.DONE) {
                return new MissDiagnosis.Diagnosis(MissReason.TRADEMARK_NOT_SERVED, null);
            }
            if (labels.mark() == null) {
                return new MissDiagnosis.Diagnosis(MissReason.MARK_NOT_LABELED, null);
            }
            return MissDiagnosis.trademark(analysis.scanner(), analysis.trademark(), analysis.input(), labels.mark(),
                    options.variantThreshold());
        }
        return new MissDiagnosis.Diagnosis(rulesBelow ? MissReason.FINDING_BELOW_THRESHOLD : MissReason.NO_RULE_FINDING,
                null);
    }

    /** ERROR is above WARNING, WARNING above INFO. */
    static boolean atOrAbove(Severity severity, Severity line) {
        return severity.ordinal() <= line.ordinal();
    }

    private static void addEvidence(List<String> evidence, String line) {
        if (evidence.size() < EVIDENCE_MAX && !evidence.contains(line)) {
            evidence.add(line);
        }
    }

    boolean measured() {
        return status == Status.CHECKED && verdict != null;
    }
}
