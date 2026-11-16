package com.thanhdatpb.listingguard.api.console;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the console home page shows (ADR-034, ADR-041), already counted. The numbers, rule ids and pack ids are counts and identifiers;
 * {@link Attention} is the one part that holds what a check printed (a SKU and the message of a finding), read from the results this
 * client stored, the way the history page reads them.
 *
 * @param weekFrom          the Monday of the current ISO week (UTC)
 * @param today             the last day counted
 * @param empty             no stored result and no verdict in the eight weeks: the page shows how to start instead of zeros
 * @param checked           listings checked this week (stored listing-rules results)
 * @param withError         of those, the results with at least one ERROR finding
 * @param trademarkHolds    retained trademark results of the week with a HIGH finding, whatever its source
 * @param holdsCatalog      of those, the ones with a HIGH finding that matched a catalog mark
 * @param holdsWatchlist    of those, the ones with a HIGH finding that matched the client's own watchlist (ADR-031)
 * @param holdsRule         of those, the ones with a HIGH finding of a context rule (no mark)
 * @param verdicts          verdicts given this week
 * @param falsePositives    of those, FALSE_POSITIVE
 * @param falsePositiveRate {@code falsePositives / verdicts}, or null with no verdict
 * @param topRules          the listing rules with the most findings over the eight weeks, with the false positives reported on them
 * @param attention         the latest results with an ERROR or a HIGH trademark risk, newest first
 * @param data              what the checks ran on
 * @param llm               LLM requests sent today against the daily limit (ADR-036); {@code enabled} is false when the LLM is off
 */
public record HomeView(LocalDate weekFrom, LocalDate today, boolean empty, long checked, long withError, long trademarkHolds,
                       long holdsCatalog, long holdsWatchlist, long holdsRule, long verdicts, long falsePositives,
                       Double falsePositiveRate, WeeklyChart chart, List<TopRule> topRules, List<Attention> attention,
                       CheckData data, com.thanhdatpb.listingguard.api.llm.LlmClient.Usage llm) {

    /** {@code withError / checked}, or null when nothing was checked. */
    public Double errorRate() {
        return checked == 0 ? null : (double) withError / checked;
    }

    /**
     * A listing rule and how often it found something. {@code falsePositives} is null when the page cannot say (more rules were marked
     * than the report lists), never zero in that case.
     */
    public record TopRule(String ruleId, String severity, long findings, Long falsePositives) {
    }

    public enum AttentionKind {
        ERROR, TRADEMARK
    }

    /**
     * One result to look at. {@code sku} is null when the result was not checked with a SKU (the editor, the API); {@code message} is
     * the finding's own text, printed as the API gave it.
     */
    public record Attention(AttentionKind kind, String sku, String ruleId, String message, String marketplace, Instant checkedAt) {

        /** The CSS and message key of the state: error or trademark. */
        public String css() {
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * What the checks run on: the trademark catalog served (state, version, and where and when its data is from), the client's enabled
     * watchlist entries, the latest re-audit and the rulesets in force. {@code catalogSource} and {@code catalogDate} are null while
     * the catalog is not served or its row cannot be read; {@code lastReauditAt} is null when the client has never re-audited.
     */
    public record CheckData(String catalogState, String catalogVersion, String catalogSource, LocalDate catalogDate,
                            int watchlistEntries, Instant lastReauditAt, Long lastReauditNew, List<RulesetVersion> rulesets) {
    }

    public record RulesetVersion(String marketplace, String version) {
    }
}
