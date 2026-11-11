package com.thanhdatpb.listingguard.api.personalization;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.Completeness;
import com.thanhdatpb.listingguard.personalization.TrademarkStatus;
import com.thanhdatpb.listingguard.personalization.config.SourceStatus;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a personalization screening: the response of {@code POST /api/v1/personalization-screenings} and
 * {@code GET /api/v1/personalization-screenings/{id}} (ADR-016). A reference warning built on an internal print policy and a
 * trademark catalog; the {@code disclaimer} says so. A pack-specific DTO that shares nothing with the other responses.
 * It never repeats the text the buyer typed: the client has it, and the offsets say where in it a finding is.
 */
@JsonPropertyOrder({"checkId", "marketplace", "rulesetVersion", "analysisVersion", "cached", "printProfile", "completeness",
        "failedRuleIds", "disclaimer", "createdAt", "expiresAt", "summary", "findings", "trademark"})
public record PersonalizationScreeningResult(
        @Schema(description = "Identifier of the stored screening; use it with GET /api/v1/personalization-screenings/{id} until expiresAt.")
        UUID checkId,
        @Schema(description = "Marketplace code, upper case.", example = "ETSY")
        String marketplace,
        @Schema(description = "Version of the personalization ruleset that produced the result.",
                example = "personalization-screening-2026-11")
        String rulesetVersion,
        @Schema(description = "Version of everything that decides the result (ruleset, code versions, trademark ruleset and catalog). "
                + "Opaque: compare for equality only. Two screenings of one client with the same marketplace, the same values and "
                + "the same analysisVersion have the same result.",
                example = "9a4e1c7d03b85f26e1d0c9b8a7f6e5d4c3b2a19087766554433221100ffeeddc")
        String analysisVersion,
        @Schema(description = "True when the stored result of an identical earlier screening is returned instead of a new analysis. "
                + "Only a COMPLETE result is ever returned from the cache.")
        boolean cached,
        @Schema(description = "The print profile that was used; the ruleset's default when the request named none.",
                example = "latin_basic")
        String printProfile,
        @Schema(description = "COMPLETE when every character rule ran and the trademark scan finished. INCOMPLETE when a character "
                + "rule failed (failedRuleIds names it), the trademark scan failed, or no trademark catalog is served "
                + "(trademark.status NOT_CONFIGURED): the findings that were established are kept. An INCOMPLETE result is never "
                + "a clean result, is never reused and costs no quota.")
        Completeness completeness,
        @Schema(description = "Character rules that failed; empty when none did.", example = "[]")
        List<String> failedRuleIds,
        @Schema(description = "Statement that the result is a reference warning and not a statement of the print workshop, a "
                + "marketplace or a court. Show it with the result.")
        String disclaimer,
        @Schema(description = "When the screening was first stored (UTC).")
        Instant createdAt,
        @Schema(description = "When the stored result stops being readable (UTC). After this instant GET answers 404 and the same "
                + "values are analysed again.")
        Instant expiresAt,
        @Schema(description = "Number of character findings per severity (the trademark findings are counted by their risk level).")
        Summary summary,
        @Schema(description = "The findings of the character rules, value by value.")
        List<CharacterFindingView> findings,
        @Schema(description = "The trademark scan of the same values. Show it next to the character findings: neither replaces the other.")
        TrademarkView trademark) {

    /** Number of character findings per severity. */
    public record Summary(int errors, int warnings, int infos) {

        static Summary of(List<CharacterFindingView> findings) {
            return new Summary(count(findings, Severity.ERROR), count(findings, Severity.WARNING),
                    count(findings, Severity.INFO));
        }

        private static int count(List<CharacterFindingView> findings, Severity severity) {
            return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
        }
    }

    /**
     * One thing a character rule found. {@code field} is {@code personalization[i]}, i being the zero-based index in the cleaned
     * list of values (an entry without text is dropped), so match by {@code label} if your own list had such entries.
     * {@code start} and {@code end} are UTF-16 code units in the text as sent, {@code end} exclusive.
     */
    @JsonPropertyOrder({"ruleId", "kind", "severity", "sourceStatus", "field", "label", "start", "end", "value", "message",
            "suggestion"})
    public record CharacterFindingView(
            @Schema(description = "Stable identifier of the rule.", example = "unsupported_characters") String ruleId,
            @Schema(description = "The case inside the rule; stable like ruleId.", example = "character_not_allowed") String kind,
            @Schema(description = "WARNING (worth a look before production) or INFO. No ERROR: nothing here is backed by an official source.")
            Severity severity,
            @Schema(description = "How well the rule is backed by documentation. Always UNVERIFIED today: it is an internal print policy.")
            SourceStatus sourceStatus,
            @Schema(description = "Which value the finding is about.", example = "personalization[0]") String field,
            @Schema(description = "The label the client gave the value, or null.", nullable = true, example = "name") String label,
            @Schema(description = "Start of the span in the text as sent (UTF-16 code units, inclusive).", example = "0") int start,
            @Schema(description = "End of the span (UTF-16 code units, exclusive).", example = "2") int end,
            @Schema(description = "The offending characters, at most a few; null for a finding that keeps no text (too long, spaces).",
                    nullable = true, example = "Đạ") String value,
            @Schema(description = "Human text, currently Vietnamese, with the real characters, positions and numbers.") String message,
            @Schema(description = "Human text, currently Vietnamese. Never proposes a replacement value.") String suggestion) {
    }

    /** The trademark half of a screening. */
    @JsonPropertyOrder({"status", "riskLevel", "catalogVersion", "rulesetVersion", "failedRuleIds", "findings"})
    public record TrademarkView(
            @Schema(description = "COMPLETE; INCOMPLETE (a trademark rule failed, findings kept); NOT_CONFIGURED (no catalog is served, "
                    + "so the scan did not run).")
            TrademarkStatus status,
            @Schema(description = "LOW, MEDIUM, HIGH or UNKNOWN. UNKNOWN means the risk could not be determined and is never LOW. "
                    + "No finding means only that the scanner found nothing in its catalog; it is no legal conclusion.")
            RiskLevel riskLevel,
            @Schema(description = "The catalog version the scan used; null when it did not run.", nullable = true) String catalogVersion,
            @Schema(description = "Version of the trademark ruleset the scan used; null when it did not run.", nullable = true,
                    example = "trademark-risk-2026-11b") String rulesetVersion,
            @Schema(description = "Trademark rules that failed; empty when none did.", example = "[]") List<String> failedRuleIds,
            List<TrademarkFindingView> findings) {
    }

    /** One trademark finding. It never contains the text the buyer typed, only where the match is and which mark it is. */
    @JsonPropertyOrder({"ruleId", "risk", "field", "label", "start", "end", "matchType", "mark", "score", "insideColorPhrase",
            "otherRuleIds", "sourceIds", "message", "suggestion"})
    public record TrademarkFindingView(
            @Schema(description = "The trademark rule.", example = "catalog_exact") String ruleId,
            @Schema(description = "Risk of this finding: LOW, MEDIUM or HIGH.") RiskLevel risk,
            @Schema(description = "Which value the finding is about.", example = "personalization[0]") String field,
            @Schema(description = "The label the client gave the value, or null.", nullable = true, example = "team") String label,
            @Schema(description = "Start of the span in the text as sent (UTF-16 code units, inclusive).", example = "0") int start,
            @Schema(description = "End of the span (UTF-16 code units, exclusive).", example = "10") int end,
            @Schema(description = "How the finding was found.") MatchType matchType,
            @Schema(description = "The catalog mark that matched (catalog data); null for a finding without a mark.", nullable = true,
                    example = "Zibblefrax") String mark,
            @Schema(description = "Similarity from 0 to 1 for a fuzzy match, 1 for an exact or phrase match; null otherwise.",
                    nullable = true, example = "1.0") Double score,
            @Schema(description = "The match lies inside a color phrase; this explains the finding and never lowers its risk.")
            boolean insideColorPhrase,
            @Schema(description = "Other rules that matched an overlapping span and lost to this one.", example = "[]")
            List<String> otherRuleIds,
            @Schema(description = "Ids of the source records of the matched catalog mark.", example = "[\"DEMO-001\"]")
            List<String> sourceIds,
            @Schema(description = "Human text, currently Vietnamese. Names the mark and the position, not the buyer's text.") String message,
            @Schema(description = "Human text, currently Vietnamese. Never proposes a replacement value.") String suggestion) {
    }

    public static PersonalizationScreeningResult of(UUID checkId, String marketplace, String rulesetVersion,
                                                    String analysisVersion, boolean cached, String printProfile,
                                                    Completeness completeness, List<String> failedRuleIds, String disclaimer,
                                                    Instant createdAt, Instant expiresAt, List<CharacterFindingView> findings,
                                                    TrademarkView trademark) {
        return new PersonalizationScreeningResult(checkId, marketplace, rulesetVersion, analysisVersion, cached, printProfile,
                completeness, failedRuleIds, disclaimer, createdAt, expiresAt, Summary.of(findings), findings, trademark);
    }
}
