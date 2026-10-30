package com.thanhdatpb.listingguard.api.trademark;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a trademark check: the response of {@code POST /api/v1/trademark-checks} and
 * {@code GET /api/v1/trademark-checks/{id}} (ADR-009). It is a reference warning, never a legal conclusion; the
 * {@code disclaimer} says so. It is a pack-specific DTO and shares nothing with the {@code /checks} response.
 */
@JsonPropertyOrder({"checkId", "marketplace", "rulesetVersion", "catalogVersion", "analysisVersion", "cached", "completeness",
        "riskLevel", "failedRuleIds", "disclaimer", "createdAt", "expiresAt", "findings"})
public record TrademarkCheckResult(
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/trademark-checks/{id} until expiresAt.")
        UUID checkId,
        @Schema(description = "Marketplace code, upper case.", example = "ETSY")
        String marketplace,
        @Schema(description = "Version of the trademark ruleset that produced the result.", example = "trademark-risk-2026-11")
        String rulesetVersion,
        @Schema(description = "Version of the trademark catalog the text was compared with. Opaque.",
                example = "synthetic-2026-11-0123456789ab")
        String catalogVersion,
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
        @Schema(description = "Statement that the result is a reference warning and not a legal conclusion. Show it with the result.")
        String disclaimer,
        @Schema(description = "When the check was first stored (UTC).")
        Instant createdAt,
        @Schema(description = "When the stored result stops being readable (UTC). After this instant GET answers 404 and the same "
                + "listing is analysed again.")
        Instant expiresAt,
        List<FindingView> findings) {

    /**
     * One possible reference. {@code field}, {@code start} and {@code end} locate it in the text the API worked on: the
     * field's value after trimming and Unicode NFC, with {@code start} inclusive and {@code end} exclusive, counted in
     * UTF-16 code units (JavaScript and Java string indexes).
     */
    @JsonPropertyOrder({"ruleId", "risk", "field", "start", "end", "snippet", "mark", "matchType", "score", "insideColorPhrase",
            "otherRuleIds", "sourceIds", "message", "suggestion"})
    public record FindingView(
            @Schema(example = "catalog_exact") String ruleId,
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
            @Schema(description = "Human text, currently Vietnamese.") String message,
            @Schema(description = "Human text, currently Vietnamese.") String suggestion) {
    }
}
