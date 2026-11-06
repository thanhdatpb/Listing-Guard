package com.thanhdatpb.listingguard.api.ruleset;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** What {@code GET /api/v1/rulesets/{marketplace}/limits} returns: the numeric limits of the ruleset that checks listings. */
public record RulesetLimitsResult(
        @Schema(description = "The marketplace code, in upper case.", example = "ETSY")
        String marketplace,
        @Schema(description = "The version of the ruleset the limits come from; the same value as `rulesetVersion` of a check. "
                + "Opaque: compare for equality, do not parse.", example = "etsy-2026-12")
        String rulesetVersion,
        @Schema(description = "The product type asked for, trimmed and as sent; null when none was asked for.", nullable = true,
                example = "LUGGAGE")
        String productType,
        @Schema(description = "Every numeric limit of the ruleset's rules, in rule order. A rule that is switched off is listed "
                + "with `enabled` false. Read the limits you need by `field` and `kind`, and ignore the ones you do not know.")
        List<LimitView> limits) {

    public static RulesetLimitsResult of(RulesetLimitsService.Limits limits) {
        return new RulesetLimitsResult(limits.marketplace(), limits.rulesetVersion(), limits.productType(),
                limits.limits().stream().map(LimitView::of).toList());
    }

    /** One numeric limit of one rule. */
    public record LimitView(
            @Schema(description = "The field of the standard input (`docs/input-schema.md`) the limit applies to, such as `title`, "
                    + "`tags`, `bullet_points` or `search_terms`. For a list field, `MAX_COUNT` limits the list and "
                    + "`MAX_LENGTH` or `MAX_BYTES` limit each item.", example = "title")
            String field,
            @Schema(description = "The rule that enforces the limit; the `ruleId` of its findings.", example = "title_length")
            String ruleId,
            @Schema(description = "What is limited: `MAX_LENGTH` (length of a text, or of each item of a list), `MAX_COUNT` "
                    + "(number of items of a list, or of words of a text), `MAX_BYTES` (size in UTF-8 bytes) or `MAX_OCCURRENCES` (how often the same "
                    + "word or character may appear).", example = "MAX_LENGTH")
            Limit.Kind kind,
            @Schema(description = "The limit.", example = "140")
            int value,
            @Schema(description = "What is counted, as the rule counts it: `CODE_POINTS` (an emoji counts once, not the UTF-16 "
                    + "length of a JavaScript string), `ITEMS`, `WORDS`, `UTF8_BYTES` or `OCCURRENCES`.", example = "CODE_POINTS")
            Limit.Unit unit,
            @Schema(description = "How a finding that breaks the limit is rated.", example = "ERROR")
            Severity severity,
            @Schema(description = "How well the number is backed by the marketplace's documentation: `VERIFIED`, `SECONDARY` or "
                    + "`UNVERIFIED`.", example = "VERIFIED")
            SourceStatus sourceStatus,
            @Schema(description = "False when the rule is switched off for this product type: nothing is enforced and the number "
                    + "is only on record. A counter should not enforce a limit that is not enabled.", example = "true")
            boolean enabled) {

        static LimitView of(RuleLimit limit) {
            return new LimitView(limit.field(), limit.ruleId(), limit.kind(), limit.value(), limit.unit(), limit.severity(),
                    limit.sourceStatus(), limit.enabled());
        }
    }
}
