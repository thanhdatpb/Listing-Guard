package com.thanhdatpb.listingguard.api.trademark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.FindingSource;
import com.thanhdatpb.listingguard.trademark.JudgeBasis;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a trademark check: the response of {@code POST /api/v1/trademark-checks} and
 * {@code GET /api/v1/trademark-checks/{id}} (ADR-009). It is a reference warning, never a legal conclusion; the
 * {@code disclaimer} says so. It is a pack-specific DTO and shares nothing with the {@code /checks} response.
 */
@JsonPropertyOrder({"checkId", "marketplace", "productType", "rulesetVersion", "catalogVersion", "catalogStatus", "analysisVersion",
        "cached", "completeness", "riskLevel", "failedRuleIds", "skippedRuleIds", "disclaimer", "createdAt", "expiresAt", "watchlist",
        "findings"})
public record TrademarkCheckResult(
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/trademark-checks/{id} until expiresAt.")
        UUID checkId,
        @Schema(description = "Marketplace code, upper case.", example = "ETSY")
        String marketplace,
        @Schema(description = "The `product_type` the request named, as the ruleset declares it; null when the request named none "
                + "(and for a result stored before product types existed).", example = "apparel", nullable = true)
        String productType,
        @Schema(description = "Version of the trademark ruleset that produced the result.", example = "trademark-risk-2026-11b")
        String rulesetVersion,
        @Schema(description = "Version of the trademark catalog the text was compared with. Opaque. `none` when no catalog was "
                + "served (`catalogStatus` NOT_CONFIGURED); a real catalog version is never `none`.",
                example = "synthetic-2026-11-0123456789ab")
        String catalogVersion,
        @Schema(description = "SERVED when the text was compared with the catalog. NOT_CONFIGURED when the deployment serves no "
                + "catalog and the check ran only the client's watchlist and the rules that need no catalog (ADR-031): the result "
                + "is then INCOMPLETE, `skippedRuleIds` names the rules that did not run, and its absence of catalog findings says "
                + "nothing.")
        CatalogStatus catalogStatus,
        @Schema(description = "Version of everything that decides the result (ruleset, algorithms, catalog, database). Opaque: "
                + "compare for equality only. Two checks of one client with the same marketplace, the same listing text and "
                + "the same analysisVersion have the same result.",
                example = "9f2c0e7a4b1d5c3e8a6f0b2d4c6e8a0b1c3d5e7f9a1b3c5d7e9f0a2b4c6d8e0f")
        String analysisVersion,
        @Schema(description = "True when the stored result of an identical earlier check is returned instead of a new analysis. "
                + "Only a COMPLETE result is ever returned from the cache.")
        boolean cached,
        @Schema(description = "COMPLETE when every rule ran. INCOMPLETE when a rule failed: the findings of the rules that "
                + "succeeded are kept, failedRuleIds names the others, and riskLevel is the highest established level or UNKNOWN.")
        Completeness completeness,
        @Schema(description = "The highest level among the findings (LOW when a complete check found nothing above LOW). "
                + "UNKNOWN means the risk could not be determined because the check is INCOMPLETE; it is never reported as LOW.")
        RiskLevel riskLevel,
        @Schema(description = "Rules that failed; empty for a COMPLETE result.", example = "[]")
        List<String> failedRuleIds,
        @Schema(description = "Rules that did not run; `catalog_distinctiveness` means tier data was unavailable to the calibrated ruleset.",
                example = "[]")
        List<String> skippedRuleIds,
        @Schema(description = "Statement that the result is a reference warning and not a legal conclusion. Show it with the result.")
        String disclaimer,
        @Schema(description = "When the check was first stored (UTC).")
        Instant createdAt,
        @Schema(description = "When the stored result stops being readable (UTC). After this instant GET answers 404 and the same "
                + "listing is analysed again.")
        Instant expiresAt,
        @Schema(description = "The client's watchlist the check used (ADR-031); null when the client had no enabled entry.",
                nullable = true)
        WatchlistUsageView watchlist,
        List<FindingView> findings) {

    /** The {@code catalogVersion} of a check made without a catalog (ADR-031). */
    public static final String NO_CATALOG = "none";

    /** Whether the catalog took part in the check. */
    public enum CatalogStatus { SERVED, NOT_CONFIGURED }

    /** A result whose catalog was served and that used no watchlist: what every result was before ADR-031. */
    public TrademarkCheckResult(UUID checkId, String marketplace, String productType, String rulesetVersion,
                                String catalogVersion, String analysisVersion, boolean cached, Completeness completeness,
                                RiskLevel riskLevel, List<String> failedRuleIds, String disclaimer, Instant createdAt,
                                Instant expiresAt, List<FindingView> findings) {
        this(checkId, marketplace, productType, rulesetVersion, catalogVersion, CatalogStatus.SERVED, analysisVersion, cached,
                completeness, riskLevel, failedRuleIds, List.of(), disclaimer, createdAt, expiresAt, null, findings);
    }

    /** The same result with other findings (the TSDR status the answer adds, ADR-009). */
    public TrademarkCheckResult withFindings(List<FindingView> other) {
        return new TrademarkCheckResult(checkId, marketplace, productType, rulesetVersion, catalogVersion, catalogStatus,
                analysisVersion, cached, completeness, riskLevel, failedRuleIds, skippedRuleIds, disclaimer, createdAt, expiresAt,
                watchlist, other);
    }

    /** The client's watchlist a check used. */
    @Schema(name = "TrademarkWatchlistUsage")
    @JsonPropertyOrder({"revision", "version", "entries"})
    public record WatchlistUsageView(
            @Schema(description = "The watchlist's revision when the check ran.", example = "3") long revision,
            @Schema(description = "SHA-256 of the enabled entries; part of `analysisVersion`.") String version,
            @Schema(description = "How many enabled entries were looked for.", example = "2") int entries) {
    }

    /** The watchlist entry a finding comes from. */
    @Schema(name = "TrademarkWatchlistMatch")
    @JsonPropertyOrder({"entryId", "mark", "matchedAlias", "niceClasses", "serialNumber", "note"})
    public record WatchlistEntryView(
            @Schema(description = "The entry's id, as `GET /api/v1/trademark-watchlist` shows it.") UUID entryId,
            @Schema(example = "Brimwick Otters") String mark,
            @Schema(description = "The alias that matched; null when the mark itself did.", nullable = true) String matchedAlias,
            @Schema(description = "The Nice classes the client noted; they do not change the level.") List<Integer> niceClasses,
            @Schema(nullable = true, example = "12345678") String serialNumber,
            @Schema(description = "Why the client listed the entry.", nullable = true) String note) {
    }

    /** A result with no product type. */
    public TrademarkCheckResult(UUID checkId, String marketplace, String rulesetVersion, String catalogVersion,
                                String analysisVersion, boolean cached, Completeness completeness, RiskLevel riskLevel,
                                List<String> failedRuleIds, String disclaimer, Instant createdAt, Instant expiresAt,
                                List<FindingView> findings) {
        this(checkId, marketplace, null, rulesetVersion, catalogVersion, analysisVersion, cached, completeness, riskLevel,
                failedRuleIds, disclaimer, createdAt, expiresAt, findings);
    }

    /**
     * One possible reference. {@code field}, {@code start} and {@code end} locate it in the text the API worked on: the
     * field's value after trimming and Unicode NFC, with {@code start} inclusive and {@code end} exclusive, counted in
     * UTF-16 code units (JavaScript and Java string indexes).
     *
     * <p>The schema has its own name: springdoc names a nested record by its simple name and merges two that share it, which
     * gave this answer the {@code /checks} finding's shape. {@code TrademarkFindingView} is already the personalization
     * screening's finding (no snippet, a label), so it cannot be used here either.
     */
    @Schema(name = "TrademarkCheckFindingView")
    @JsonPropertyOrder({"ruleId", "source", "risk", "field", "start", "end", "snippet", "mark", "matchType", "score",
            "insideColorPhrase", "otherRuleIds", "sourceIds", "sourceLinks", "riskAdjustments", "watchlistEntry", "message",
            "suggestion", "tsdrStatuses", "ownerCountSameMark"})
    public record FindingView(
            @Schema(example = "catalog_exact") String ruleId,
            @Schema(description = "Where the finding comes from: CATALOG (a mark of the catalog), CONTEXT (a rule about place, sport "
                    + "or color) or WATCHLIST (an entry of the client's own watchlist, rule `watchlist_match`, ADR-031).")
            FindingSource source,
            @Schema(description = "Level of this finding: LOW (informational), MEDIUM or HIGH.") RiskLevel risk,
            @Schema(description = "Field path: title, description, brand, search_terms, tags[i], bullet_points[i] (zero-based, in "
                    + "the cleaned list: blank entries are dropped).", example = "bullet_points[1]") String field,
            @Schema(description = "Start of the span in the field text (UTF-16 code units, inclusive).", example = "9") int start,
            @Schema(description = "End of the span in the field text (UTF-16 code units, exclusive).", example = "15") int end,
            @Schema(description = "The text of the span, cut to a length limit.", example = "Zorvex") String snippet,
            @Schema(description = "The catalog mark that matched; null for a finding that has no mark.", example = "Zorvex",
                    nullable = true) String mark,
            @Schema(description = "How it was found: EXACT, PHRASE, FUZZY (similar to a mark) or CONTEXT (a rule about place, sport or color).")
            MatchType matchType,
            @Schema(description = "Similarity from 0 to 1: 1.0 for EXACT and PHRASE, the final score for FUZZY, null for CONTEXT.",
                    nullable = true) Double score,
            @Schema(description = "The match lies inside a color description. This explains the finding and never lowers its level.")
            boolean insideColorPhrase,
            @Schema(description = "Other rules that matched an overlapping span of the same field and lost to this finding.")
            List<String> otherRuleIds,
            @Schema(description = "Ids of the source records of the catalog mark; empty when there is no mark.") List<String> sourceIds,
            @Schema(description = "Public look-up pages for the source records, one per source id the ruleset has an address for "
                    + "(today an id `uspto:<serial number>` links to its USPTO TSDR page); empty when none has. A link is built "
                    + "from the ruleset and is not checked: open it to read the record, and treat it as unverified.")
            List<SourceLinkView> sourceLinks,
            @Schema(description = "Why `risk` is lower than the level the rule assigned; empty when it is not lower. Each entry says "
                    + "the original level (`from`) and the level that reason alone gives (`to`); `risk` is the lowest `to`.")
            List<RiskAdjustmentView> riskAdjustments,
            @Schema(description = "The watchlist entry the finding comes from; null for any finding whose `source` is not WATCHLIST.",
                    nullable = true)
            WatchlistEntryView watchlistEntry,
            @Schema(description = "Human text, currently Vietnamese.") String message,
            @Schema(description = "Human text, currently Vietnamese.") String suggestion,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "The last status the USPTO TSDR gave for the source records of the mark, one entry for each source "
                            + "id that has been checked. **Present only when the deployment turned it on "
                            + "(`listing-guard.trademark.tsdr.finding-status.enabled`); absent otherwise.** When present it can "
                            + "be empty. It is information read when this answer is built, not part of the stored result: it "
                            + "never changes `risk`, `analysisVersion` or the cache, and the same stored result can show a "
                            + "newer status later. Read `checkedAt`: the status can be old.")
            List<TsdrStatusView> tsdrStatuses,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Estimated distinct current owner names for the matched mark in the catalog snapshot.")
            Long ownerCountSameMark) {

        public boolean weakCalibrated() {
            return risk == RiskLevel.LOW && riskAdjustments.stream()
                    .anyMatch(adjustment -> adjustment.reason().equals("catalog_weak_exact"));
        }

        public boolean catalogCalibrated() {
            return riskAdjustments.stream().anyMatch(adjustment -> adjustment.reason().equals("catalog_weak_exact")
                    || adjustment.reason().equals("catalog_ordinary_exact"));
        }

        /** A finding with no source link, no adjustment and no TSDR status member. */
        public FindingView(String ruleId, RiskLevel risk, String field, int start, int end, String snippet, String mark,
                           MatchType matchType, Double score, boolean insideColorPhrase, List<String> otherRuleIds,
                           List<String> sourceIds, String message, String suggestion) {
            this(ruleId, risk, field, start, end, snippet, mark, matchType, score, insideColorPhrase, otherRuleIds, sourceIds,
                    List.of(), List.of(), null, message, suggestion);
        }

        /** A finding of a rule of the ruleset as it is stored: no watchlist entry, no TSDR status member. */
        public FindingView(String ruleId, RiskLevel risk, String field, int start, int end, String snippet, String mark,
                           MatchType matchType, Double score, boolean insideColorPhrase, List<String> otherRuleIds,
                           List<String> sourceIds, List<SourceLinkView> sourceLinks, List<RiskAdjustmentView> riskAdjustments,
                           String message, String suggestion) {
            this(ruleId, risk, field, start, end, snippet, mark, matchType, score, insideColorPhrase, otherRuleIds, sourceIds,
                    sourceLinks, riskAdjustments, null, message, suggestion);
        }

        /** A finding as it is stored: the source follows from the rule id, the TSDR status member is not part of it. */
        public FindingView(String ruleId, RiskLevel risk, String field, int start, int end, String snippet, String mark,
                           MatchType matchType, Double score, boolean insideColorPhrase, List<String> otherRuleIds,
                           List<String> sourceIds, List<SourceLinkView> sourceLinks, List<RiskAdjustmentView> riskAdjustments,
                           WatchlistEntryView watchlistEntry, String message, String suggestion) {
            this(ruleId, risk, field, start, end, snippet, mark, matchType, score, insideColorPhrase, otherRuleIds, sourceIds,
                    sourceLinks, riskAdjustments, watchlistEntry, message, suggestion, null);
        }

        public FindingView(String ruleId, RiskLevel risk, String field, int start, int end, String snippet, String mark,
                           MatchType matchType, Double score, boolean insideColorPhrase, List<String> otherRuleIds,
                           List<String> sourceIds, List<SourceLinkView> sourceLinks, List<RiskAdjustmentView> riskAdjustments,
                           WatchlistEntryView watchlistEntry, String message, String suggestion, Long ownerCountSameMark) {
            this(ruleId, FindingSource.of(ruleId), risk, field, start, end, snippet, mark, matchType, score, insideColorPhrase,
                    otherRuleIds, sourceIds, sourceLinks, riskAdjustments, watchlistEntry, message, suggestion, null, ownerCountSameMark);
        }

        /** The same finding with the TSDR status member set (ADR-009, "TSDR status check"). */
        public FindingView withTsdrStatuses(List<TsdrStatusView> statuses) {
            return new FindingView(ruleId, source, risk, field, start, end, snippet, mark, matchType, score, insideColorPhrase,
                    otherRuleIds, sourceIds, sourceLinks, riskAdjustments, watchlistEntry, message, suggestion, statuses, ownerCountSameMark);
        }
    }

    /** The last status the USPTO TSDR gave for one source record of a catalog mark. */
    @JsonPropertyOrder({"sourceId", "statusCode", "statusDescription", "statusClass", "statusDate", "checkedAt"})
    public record TsdrStatusView(
            @Schema(description = "The source id the status is for.", example = "uspto:12345678") String sourceId,
            @Schema(description = "The status code the USPTO gave.", example = "700") String statusCode,
            @Schema(description = "The USPTO's own description of the status; null when the answer had none.",
                    example = "REGISTERED", nullable = true) String statusDescription,
            @Schema(description = "LIVE (registered or pending), DEAD (abandoned, cancelled or expired) or UNKNOWN (a code the "
                    + "deployment's status map does not settle). Decided from the code by a versioned, unverified map, never from "
                    + "the description; it is information and does not change the finding's level.",
                    allowableValues = {"LIVE", "DEAD", "UNKNOWN"}) String statusClass,
            @Schema(description = "The date of the status the USPTO gave (UTC date); null when the answer had none.",
                    nullable = true) LocalDate statusDate,
            @Schema(description = "When this deployment asked the TSDR (or read a file saved from it) (UTC).") Instant checkedAt) {
    }

    /** One reason a finding is reported lower than the level its rule assigned. */
    @JsonPropertyOrder({"reason", "from", "to", "note"})
    public record RiskAdjustmentView(
            @Schema(description = "`outside_product_classes`: the request named a product type and none of the mark's Nice classes is "
                    + "one of the product type's. `common_word`: the mark is an ordinary word of the ruleset's list and the match is "
                    + "not an exact or phrase match in a brand field. `catalog_weak_exact` and `catalog_ordinary_exact`: "
                    + "unverified catalog tier caps for an eligible single-token exact match. `llm_descriptive`: a language model read the sentence around "
                    + "the match and thinks the word describes the product (only when the operator turned the LLM judge on, "
                    + "ADR-036); the finding is kept and its note starts with the label.", example = "outside_product_classes") String reason,
            @Schema(description = "The level the rule assigned before any adjustment.") RiskLevel from,
            @Schema(description = "The level this reason alone gives; lower than `from`.") RiskLevel to,
            @Schema(description = "Human text, currently Vietnamese.") String note,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "Unverified catalog data used for a tier-based adjustment.") CalibrationEvidence distinctiveness,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "Only on `llm_descriptive` when the operator chose the evidence prompt of the LLM judge "
                            + "(ADR-051): what the model was shown besides the sentence, and its reason.") JudgeEvidenceView judgeEvidence) {
        public RiskAdjustmentView(String reason, RiskLevel from, RiskLevel to, String note) {
            this(reason, from, to, note, null, null);
        }

        public RiskAdjustmentView(String reason, RiskLevel from, RiskLevel to, String note, CalibrationEvidence distinctiveness) {
            this(reason, from, to, note, distinctiveness, null);
        }
    }

    /**
     * What the LLM judge was given with the sentence when it lowered a finding (ADR-051), and why it did. The evidence is computed
     * by the program, not by the model, and each field that was not available is {@code unknown}. It holds no prompt, no sentence of
     * the listing and no mark.
     */
    @Schema(name = "TrademarkJudgeEvidence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JudgeEvidenceView(
            @Schema(description = "The `version` of the judge prompt file that was used.", example = "trademark-judge-2026-12")
            String promptVersion,
            @Schema(description = "The evidence fields as they were sent, each a string: `distinctiveness_tier`, `same_mark_count`, "
                    + "`same_mark_class_count`, `mark_kind`, `dictionary_word`, `word_count`, `product_class_overlap`, `tsdr_status` "
                    + "and `verified_case`. `same_mark_count` counts live USPTO serial records, not owners. A value the program did "
                    + "not have is `unknown`. These are hints for the model; no rule uses them.")
            Map<String, String> evidence,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "The model's reason after the schema check (at most 200 characters, Vietnamese); "
                            + "the same text the note ends with.") String reason) {

        static JudgeEvidenceView of(JudgeBasis basis) {
            return basis == null ? null : new JudgeEvidenceView(basis.promptVersion(), basis.evidence(), basis.reason());
        }
    }

    @Schema(name = "TrademarkCalibrationEvidence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CalibrationEvidence(DistinctivenessSignals.Tier tier, Integer wordCount, Long sameMarkCount,
                                      Integer sameMarkClassCount, DistinctivenessSignals.MarkKind markKind,
                                      Boolean dictionaryWord, DistinctivenessSignals.Reason reason, Long ownerCountSameMark) {
        static CalibrationEvidence of(DistinctivenessSignals signals) {
            return signals == null ? null : new CalibrationEvidence(signals.tier(), signals.wordCount(),
                    signals.sameMarkCount(), signals.sameMarkClassCount(), signals.markKind(),
                    signals.dictionaryWord(), signals.reason(), signals.ownerCountSameMark());
        }
    }

    /** A public look-up page for the source record of a catalog mark. */
    @JsonPropertyOrder({"sourceId", "label", "url"})
    public record SourceLinkView(
            @Schema(description = "The source id the link was built from.", example = "uspto:12345678") String sourceId,
            @Schema(description = "What the page is.", example = "USPTO TSDR") String label,
            @Schema(description = "The address. Built from a template of the ruleset, only for a source the ruleset names.",
                    example = "https://tsdr.uspto.gov/#caseNumber=12345678&caseSearchType=US_APPLICATION&caseType=DEFAULT&searchType=statusSearch")
            String url) {
    }
}
