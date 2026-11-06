package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import java.util.List;

/**
 * The limits the editor shows next to a field (counters, the preview). They are the limits that
 * {@code GET /api/v1/rulesets/{marketplace}/limits} returns, taken from the same {@code RulesetLimitsService}: every number is
 * read from the active ruleset by the rule that enforces it, never written in Java (ADR-001), and for Amazon the product type
 * override of the ruleset applies, as it does to the check itself.
 *
 * <p>A limit is {@code null} when no enabled rule declares it for the field: the ruleset has no such rule, the rule is switched
 * off, or the marketplace has no such field (Etsy has tags, Amazon has bullet points and search terms). The editor then shows no
 * counter for it. When several enabled rules limit the same thing, the strictest wins.
 */
public record EditorLimits(
        Integer titleMax,
        Integer tagLengthMax,
        Integer tagCountMax,
        Integer bulletCountMax,
        Integer bulletLengthMax,
        Integer searchTermsMaxBytes) {

    public static EditorLimits of(List<RuleLimit> limits) {
        return new EditorLimits(
                strictest(limits, "title", Limit.Kind.MAX_LENGTH),
                strictest(limits, "tags", Limit.Kind.MAX_LENGTH),
                strictest(limits, "tags", Limit.Kind.MAX_COUNT),
                strictest(limits, "bullet_points", Limit.Kind.MAX_COUNT),
                strictest(limits, "bullet_points", Limit.Kind.MAX_LENGTH),
                strictest(limits, "search_terms", Limit.Kind.MAX_BYTES));
    }

    private static Integer strictest(List<RuleLimit> limits, String field, Limit.Kind kind) {
        return limits.stream()
                .filter(limit -> limit.enabled() && limit.kind() == kind && limit.field().equals(field))
                .map(RuleLimit::value)
                .min(Integer::compare)
                .orElse(null);
    }
}
