package com.thanhdatpb.listingguard.api.check;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonView;
import com.thanhdatpb.listingguard.core.finding.FixAction;
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
        "createdAt", "summary", "findings", "fixesComputed", "readiness"})
public record CheckResult(
        @JsonView(LegacyView.class)
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/checks/{id}.")
        UUID checkId,
        @JsonView(LegacyView.class)
        CheckStatus status,
        @JsonView(LegacyView.class)
        @Schema(description = "Marketplace code, upper case.", example = "ETSY")
        String marketplace,
        @JsonView(LegacyView.class)
        @Schema(description = "Version of the ruleset that produced the findings. Null for checks stored before versions were recorded.",
                example = "etsy-2026-11", nullable = true)
        String rulesetVersion,
        @JsonView(LegacyView.class)
        @Schema(description = "Version of the whole analysis that produced the result. Currently equal to rulesetVersion; it will "
                + "also change when other analyzers (for example an LLM step) are added. Two checks with the same "
                + "marketplace, contentHash and analysisVersion have the same result.",
                example = "etsy-2026-11", nullable = true)
        String analysisVersion,
        @JsonView(LegacyView.class)
        @Schema(description = "SHA-256 of the normalized listing content.")
        String contentHash,
        @JsonView(LegacyView.class)
        @Schema(description = "True when an identical listing had already been checked with the same analysisVersion and the stored "
                + "result is returned instead of a new check.")
        boolean cached,
        @JsonView(LegacyView.class)
        @Schema(description = "When the check was first stored.")
        Instant createdAt,
        @JsonView(LegacyView.class)
        Summary summary,
        @JsonView(LegacyView.class)
        List<FindingView> findings,
        @JsonView(DetailedView.class)
        @Schema(description = "Whether verified fixes were computed for this result.", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean fixesComputed,
        @JsonView(DetailedView.class)
        @Schema(description = "Readiness from the listing-rules pack only; null when this legacy result has not been enriched.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        ReadinessView readiness) {

    public CheckResult(UUID checkId, CheckStatus status, String marketplace, String rulesetVersion, String analysisVersion,
                       String contentHash, boolean cached, Instant createdAt, Summary summary, List<FindingView> findings) {
        this(checkId, status, marketplace, rulesetVersion, analysisVersion, contentHash, cached, createdAt, summary,
                findings, null, null);
    }

    /** Number of findings per severity. */
    public record Summary(int errors, int warnings, int infos) {

        @JsonView(LegacyView.class)
        public int errors() {
            return errors;
        }

        @JsonView(LegacyView.class)
        public int warnings() {
            return warnings;
        }

        @JsonView(LegacyView.class)
        public int infos() {
            return infos;
        }

        static Summary of(List<FindingView> findings) {
            return new Summary(count(findings, Severity.ERROR), count(findings, Severity.WARNING), count(findings, Severity.INFO));
        }

        private static int count(List<FindingView> findings, Severity severity) {
            return (int) findings.stream().filter(finding -> finding.severity() == severity).count();
        }
    }

    public record ReadinessView(
            @JsonView(DetailedView.class)
            @Schema(nullable = true, description = "Null when status is INCOMPLETE.")
            Integer score,
            @JsonView(DetailedView.class)
            boolean ready,
            @JsonView(DetailedView.class)
            String status,
            @JsonView(DetailedView.class)
            String version) {
    }

    public record FixView(
            @JsonView(DetailedView.class)
            FixAction action,
            @JsonView(DetailedView.class)
            String description,
            @JsonView(DetailedView.class)
            @Schema(nullable = true, description = "Proposed replacement; null for REMOVE.")
            String proposedValue) {
    }

    public static class LegacyView {
    }

    public static final class DetailedView extends LegacyView {
    }

    /**
     * One problem found in the listing. {@code field} says where; {@code value} is the exact value that caused it, so a
     * client can match by value and not depend on positions.
     */
    @JsonPropertyOrder({"ruleId", "source", "severity", "field", "value", "message", "suggestion", "fix"})
    public record FindingView(
            @JsonView(LegacyView.class)
            @Schema(example = "tag_length") String ruleId,
            @JsonView(LegacyView.class)
            FindingSource source,
            @JsonView(LegacyView.class)
            Severity severity,
            @JsonView(LegacyView.class)
            @Schema(description = "Field path, see the integration guide.", example = "tags[1]") String field,
            @JsonView(LegacyView.class)
            @Schema(description = "The value that caused the finding; null when no single value applies (a missing field).",
                    example = "a tag that is far too long for the limit", nullable = true) String value,
            @JsonView(LegacyView.class)
            @Schema(example = "Tag 'a tag that is far too long...' dài 38 ký tự, tối đa 20.") String message,
            @JsonView(LegacyView.class)
            @Schema(example = "Rút ngắn tag này ít nhất 18 ký tự.") String suggestion,
            @JsonView(DetailedView.class)
            @Schema(description = "A verified suggestion for this finding; null when no certain fix exists.", nullable = true)
            FixView fix) {

        public FindingView(String ruleId, FindingSource source, Severity severity, String field, String value,
                           String message, String suggestion) {
            this(ruleId, source, severity, field, value, message, suggestion, null);
        }
    }
}
