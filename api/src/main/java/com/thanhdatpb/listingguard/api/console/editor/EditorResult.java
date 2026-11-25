package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import java.util.List;
import java.util.Locale;

/**
 * What the "Check listing" page shows after a check, already reduced to text the template prints (ADR-014, part B3).
 * The readiness panel and the trademark panel are always both present: a score never stands alone, and a trademark result
 * that could not be produced says why instead of leaving a gap.
 *
 * @param rulesRan              false when the listing rules did not run (quota or plan), so there are no rule findings
 * @param requiredFieldsFinding true when a {@code required_fields} finding exists, in the listing-rules result
 * @param listing               the listing as it was checked, with the text the findings point at marked (ADR-041); the page adds
 *                              the rules' limits to it
 */
public record EditorResult(Readiness readiness, Trademark trademark, List<Group<RuleItem>> ruleGroups, boolean rulesRan,
                           boolean requiredFieldsFinding, com.thanhdatpb.listingguard.api.console.ListingView listing) {

    public enum Status {
        PASSED, REVIEW_REQUIRED, INCOMPLETE, ERROR;

        public String messageKey() {
            return "editor.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Console summary. Pack-specific details remain visible alongside it. */
    public Status status() {
        if (count("error") > 0) {
            return Status.ERROR;
        }
        if (!rulesRan || readiness.kind() == ReadinessKind.INCOMPLETE || readiness.kind() == ReadinessKind.NOT_RUN
                || trademark.kind() != TrademarkKind.RESULT || "UNKNOWN".equals(trademark.riskLevel())) {
            return Status.INCOMPLETE;
        }
        if (readiness.kind() == ReadinessKind.NOT_READY || count("warning") > 0 || trademarkCount() > 0
                || "MEDIUM".equals(trademark.riskLevel()) || "HIGH".equals(trademark.riskLevel())) {
            return Status.REVIEW_REQUIRED;
        }
        return Status.PASSED;
    }

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
    public record Readiness(ReadinessKind kind, Integer score, String big, String headline, String detail, String version,
                            java.util.UUID checkId) {
        public Readiness(ReadinessKind kind, Integer score, String big, String headline, String detail, String version) {
            this(kind, score, big, headline, detail, version, null);
        }
    }

    /** {@code riskLevel} is null when no level was established; {@code big} is what the panel prints large. */
    public record Trademark(TrademarkKind kind, String riskLevel, String big, String headline, String detail, String hint,
                            String disclaimer, List<Group<TrademarkItem>> groups, java.util.UUID checkId) {
        public Trademark(TrademarkKind kind, String riskLevel, String big, String headline, String detail, String hint,
                         String disclaimer, List<Group<TrademarkItem>> groups) {
            this(kind, riskLevel, big, headline, detail, hint, disclaimer, groups, null);
        }
    }

    public record Group<T>(String label, String css, List<T> items) {
    }

    /** A span of text with the part that matters in the middle; the template marks {@code match}. */
    public record Evidence(String before, String match, String after, boolean cutBefore, boolean cutAfter) {
    }

    public record Fix(String action, String description, String proposedValue) {
    }

    /**
     * One finding of the listing rules; {@code value} is the value it is about (null when no single value applies).
     * {@code fixId} numbers the findings that carry a fix, in the order the page shows them (null when there is none); the
     * page uses it to name the form values of the "Apply suggestion" button. {@code target} is the address of the stored finding
     * the feedback widget answers (ADR-034).
     */
    public record RuleItem(String ruleId, String field, String message, String suggestion, String value, Evidence evidence,
                           Fix fix, Integer fixId, FeedbackTarget target, String source) {
    }

    /**
     * {@code target} is the address of the stored finding the feedback widget answers (ADR-034). {@code llmReason} is the reason of
     * a language model that called the word descriptive (ADR-036): an empty text when it gave none, null when the finding has no
     * LLM label.
     */
    public record TrademarkItem(String ruleId, String risk, String field, String matchType, String mark, String message,
                                String suggestion, Evidence evidence, FeedbackTarget target, String llmReason, String source, List<String> sourceIds, boolean weakCalibrated) {
    }

    /** How many findings of the listing rules have this level ({@code error}, {@code warning} or {@code info}). */
    public int count(String level) {
        return ruleGroups.stream().filter(group -> level.equals(group.css())).mapToInt(group -> group.items().size()).sum();
    }

    /** How many trademark findings there are, of any risk. */
    public int trademarkCount() {
        return trademark.groups().stream().mapToInt(group -> group.items().size()).sum();
    }

    /**
     * True when the rewrite button applies (ADR-037): the listing rules reported an ERROR or the trademark check reported a finding.
     * The page also needs the feature to be on.
     */
    public boolean needsRewrite() {
        return ruleGroups.stream().anyMatch(group -> "error".equals(group.css()) && !group.items().isEmpty())
                || trademark.groups().stream().anyMatch(group -> !group.items().isEmpty());
    }

    /** The finding the rewrite starts from (ADR-037): the first ERROR of the rules, else the first trademark finding; null when none. */
    public FeedbackTarget rewriteAnchor() {
        return java.util.stream.Stream.concat(
                        ruleGroups.stream().filter(group -> "error".equals(group.css())).flatMap(group -> group.items().stream())
                                .map(RuleItem::target),
                        trademark.groups().stream().flatMap(group -> group.items().stream()).map(TrademarkItem::target))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Every finding of the page that the feedback widget can answer, in the order shown. */
    public List<FeedbackTarget> targets() {
        List<FeedbackTarget> targets = new java.util.ArrayList<>();
        ruleGroups.forEach(group -> group.items().forEach(item -> targets.add(item.target())));
        trademark.groups().forEach(group -> group.items().forEach(item -> targets.add(item.target())));
        return targets.stream().filter(java.util.Objects::nonNull).toList();
    }
}
