package com.thanhdatpb.listingguard.api.check;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.core.finding.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of checking a listing: the stable response of {@code POST /api/v1/checks} and
 * {@code GET /api/v1/checks/{id}}.
 */
@JsonPropertyOrder({"checkId", "status", "marketplace", "rulesetVersion", "analysisVersion", "contentHash", "cached",
        "createdAt", "summary", "findings"})
public record CheckResult(
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/checks/{id}.")
        UUID checkId,
        CheckStatus status,
        @Schema(description = "Marketplace code, upper case.", example = "ETSY")
        String marketplace,
        @Schema(description = "Version of the ruleset that produced the findings. Null for checks stored before versions were recorded.",
                example = "etsy-2026-11", nullable = true)
        String rulesetVersion,
        @Schema(description = "Version of the whole analysis that produced the result. Currently equal to rulesetVersion; it will "
                + "also change when other analyzers (for example an LLM step) are added. Two checks with the same "
                + "marketplace, contentHash and analysisVersion have the same result.",
                example = "etsy-2026-11", nullable = true)
        String analysisVersion,
        @Schema(description = "SHA-256 of the normalized listing content.")
        String contentHash,
        @Schema(description = "True when an identical listing had already been checked with the same analysisVersion and the stored "
                + "result is returned instead of a new check.")
        boolean cached,
        @Schema(description = "When the check was first stored.")
        Instant createdAt,
        Summary summary,
        List<FindingView> findings) {

    /** Number of findings per severity. */
    public record Summary(int errors, int warnings, int infos) {

        static Summary of(List<FindingView> findings) {
            return new Summary(count(findings, Severity.ERROR), count(findings, Severity.WARNING), count(findings, Severity.INFO));
        }

        private static int count(List<FindingView> findings, Severity severity) {
            return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
        }
    }

    /**
     * One problem found in the listing. {@code field} says where; {@code value} is the exact value that caused it, so a
     * client can match by value and not depend on positions.
     */
    @JsonPropertyOrder({"ruleId", "source", "severity", "field", "value", "message", "suggestion"})
    public record FindingView(
            @Schema(example = "tag_length") String ruleId,
            FindingSource source,
            Severity severity,
            @Schema(description = "Field path, see the integration guide.", example = "tags[1]") String field,
            @Schema(description = "The value that caused the finding; null when no single value applies (a missing field).",
                    example = "a tag that is far too long for the limit", nullable = true) String value,
            @Schema(example = "Tag 'a tag that is far too long...' dài 38 ký tự, tối đa 20.") String message,
            @Schema(example = "Rút ngắn tag này ít nhất 18 ký tự.") String suggestion) {
    }
}
