package com.thanhdatpb.listingguard.api.check;

import java.util.List;

/**
 * A listing-rules result with the scope it was made under (ADR-052). {@code result} is the stored result as {@code CheckService} always
 * returns it; the scope and what was left out are kept beside it, so {@link CheckResult}, which is the body of {@code /api/v1/checks},
 * gains no member.
 *
 * @param scope           how much of the listing the check covered
 * @param skippedRuleIds  the rules that left something out (empty for a full check, and for a content check that left nothing out)
 * @param skippedFields   the fields they left out, in ruleset order
 */
public record ScopedCheckResult(CheckResult result, CheckScope.Kind scope, List<String> skippedRuleIds, List<String> skippedFields) {

    public ScopedCheckResult {
        skippedRuleIds = List.copyOf(skippedRuleIds);
        skippedFields = List.copyOf(skippedFields);
    }

    /** A result of the full check: nothing was left out. */
    public static ScopedCheckResult full(CheckResult result) {
        return new ScopedCheckResult(result, CheckScope.Kind.FULL, List.of(), List.of());
    }

    /** True when the result says nothing about some fields. */
    public boolean partial() {
        return !skippedFields.isEmpty();
    }
}
