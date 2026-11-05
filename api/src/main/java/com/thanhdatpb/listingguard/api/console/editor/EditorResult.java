package com.thanhdatpb.listingguard.api.console.editor;

import java.util.List;
import java.util.Locale;

/**
 * What the "Check listing" page shows after a check, already reduced to text the template prints (ADR-014, part B3).
 * The readiness panel and the trademark panel are always both present: a score never stands alone, and a trademark result
 * that could not be produced says why instead of leaving a gap.
 *
 * @param rulesRan              false when the listing rules did not run (quota or plan), so there are no rule findings
 * @param requiredFieldsFinding true when a {@code required_fields} finding exists, which opens "Other listing fields"
 */
public record EditorResult(Readiness readiness, Trademark trademark, List<Group<RuleItem>> ruleGroups, boolean rulesRan,
                           boolean requiredFieldsFinding) {

    public enum ReadinessKind {
        READY, NOT_READY, INCOMPLETE, NOT_RUN;

        public String css() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public enum TrademarkKind {
        RESULT, INCOMPLETE, UNAVAILABLE, NOT_IN_PLAN, QUOTA_EXCEEDED;

        public String css() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** {@code score} is null whenever there is no number to show (INCOMPLETE, or the rules did not run). */
    public record Readiness(ReadinessKind kind, Integer score, String big, String headline, String detail, String version) {
    }

    /** {@code riskLevel} is null when no level was established; {@code big} is what the panel prints large. */
    public record Trademark(TrademarkKind kind, String riskLevel, String big, String headline, String detail, String hint,
                            String disclaimer, List<Group<TrademarkItem>> groups) {
    }

    public record Group<T>(String label, String css, List<T> items) {
    }

    /** A span of text with the part that matters in the middle; the template marks {@code match}. */
    public record Evidence(String before, String match, String after, boolean cutBefore, boolean cutAfter) {
    }

    public record Fix(String action, String description, String proposedValue) {
    }

    /** One finding of the listing rules; {@code value} is the value it is about (null when no single value applies). */
    public record RuleItem(String ruleId, String field, String message, String suggestion, String value, Evidence evidence,
                           Fix fix) {
    }

    public record TrademarkItem(String ruleId, String risk, String field, String matchType, String mark, String message,
                                String suggestion, Evidence evidence) {
    }
}
