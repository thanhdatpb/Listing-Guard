package com.thanhdatpb.listingguard.api.variation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.Completeness;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a variation check: the response of {@code POST /api/v1/variation-checks} and
 * {@code GET /api/v1/variation-checks/{id}} (ADR-015). A reference warning built on secondary sources and internal
 * heuristics; the {@code disclaimer} says so. A pack-specific DTO that shares nothing with the {@code /checks} response.
 */
@JsonPropertyOrder({"checkId", "marketplace", "rulesetVersion", "analysisVersion", "cached", "completeness", "failedRuleIds",
        "disclaimer", "createdAt", "expiresAt", "summary", "findings"})
public record VariationCheckResult(
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/variation-checks/{id} until expiresAt.")
        UUID checkId,
        @Schema(description = "Marketplace code, upper case.", example = "AMAZON")
        String marketplace,
        @Schema(description = "Version of the variation ruleset that produced the result.", example = "variation-checks-2026-11")
        String rulesetVersion,
        @Schema(description = "Version of everything that decides the result (ruleset, comparison logic, input format). Opaque: "
                + "compare for equality only. Two checks of one client with the same marketplace, the same family and the same "
                + "analysisVersion have the same result.",
                example = "5b0e3d79c1a24f6e8d7c9b0a1f2e3d4c5b6a79881726354453627180911a2b3c")
        String analysisVersion,
        @Schema(description = "True when the stored result of an identical earlier check is returned instead of a new analysis. "
                + "Only a COMPLETE result is ever returned from the cache.")
        boolean cached,
        @Schema(description = "COMPLETE when every rule ran. INCOMPLETE when a rule failed: the findings of the rules that "
                + "succeeded are kept and failedRuleIds names the others. An INCOMPLETE result is never a clean result.")
        Completeness completeness,
        @Schema(description = "Rules that failed; empty for a COMPLETE result.", example = "[]")
        List<String> failedRuleIds,
        @Schema(description = "Statement that the result is a reference warning and not a statement of the marketplace. Show it with the result.")
        String disclaimer,
        @Schema(description = "When the check was first stored (UTC).")
        Instant createdAt,
        @Schema(description = "When the stored result stops being readable (UTC). After this instant GET answers 404 and the same "
                + "family is analysed again.")
        Instant expiresAt,
        Summary summary,
        List<FindingView> findings) {

    /** Number of findings per severity. */
    public record Summary(int errors, int warnings, int infos) {

        static Summary of(List<FindingView> findings) {
            return new Summary(count(findings, Severity.ERROR), count(findings, Severity.WARNING),
                    count(findings, Severity.INFO));
        }

        private static int count(List<FindingView> findings, Severity severity) {
            return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
        }
    }

    /**
     * One thing a rule found. {@code field} is a path in the cleaned request: {@code parent.sku}, {@code children[3].sku},
     * {@code children[3].attributes.size}, {@code variation_properties[1]}; indexes are zero-based and count the cleaned lists
     * (blank entries are dropped), so match by {@code value} if your own list had blanks.
     */
    @JsonPropertyOrder({"ruleId", "kind", "severity", "sourceStatus", "field", "value", "relatedFields", "message", "suggestion"})
    public record FindingView(
            @Schema(description = "Stable identifier of the rule.", example = "parent_child_structure") String ruleId,
            @Schema(description = "The case inside the rule; stable like ruleId.", example = "child_parent_sku_mismatch") String kind,
            @Schema(description = "ERROR (only for a verified rule; none today), WARNING (worth fixing) or INFO.") Severity severity,
            @Schema(description = "How well the rule is backed by the marketplace's documentation: VERIFIED (an official page), "
                    + "SECONDARY (consistent secondary sources) or UNVERIFIED (our own check).") SourceStatus sourceStatus,
            @Schema(description = "Where the problem is.", example = "children[2].parent_sku") String field,
            @Schema(description = "The offending text, cut to a length limit; null when no single value applies.", nullable = true,
                    example = "OTHER-P") String value,
            @Schema(description = "Other places the message refers to, for example the first child with the same combination.",
                    example = "[\"parent.sku\"]") List<String> relatedFields,
            @Schema(description = "Human text, currently Vietnamese, with the real values.") String message,
            @Schema(description = "Human text, currently Vietnamese. Never proposes a replacement value.") String suggestion) {
    }

    public static VariationCheckResult of(UUID checkId, String marketplace, String rulesetVersion, String analysisVersion,
                                          boolean cached, Completeness completeness, List<String> failedRuleIds,
                                          String disclaimer, Instant createdAt, Instant expiresAt,
                                          List<FindingView> findings) {
        return new VariationCheckResult(checkId, marketplace, rulesetVersion, analysisVersion, cached, completeness,
                failedRuleIds, disclaimer, createdAt, expiresAt, Summary.of(findings), findings);
    }
}
