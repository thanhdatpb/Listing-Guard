package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.Usage;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * What one press of "Check" costs the client, from its plan (ADR-013): one unit for the listing rules, and one more for the
 * trademark check when the plan has the {@code trademark-risk} pack. A result that is already stored (the same listing
 * checked before) is free, so this is the most a press can cost. When plans are off nothing is counted.
 */
public record EditorCost(boolean enforced, int units, boolean trademarkIncluded, String plan, Integer monthlyQuota,
                         Long remaining, String resetDate) {

    public static EditorCost of(Usage usage) {
        boolean trademark = usage.packs().contains(Pack.TRADEMARK_RISK.id());
        return new EditorCost(usage.enforced(), trademark ? 2 : 1, trademark, usage.plan(), usage.monthlyQuota(),
                usage.remaining(), resetDate(usage.resetAt()));
    }

    /** The day the quota resets, as {@code yyyy-MM-dd} in UTC (the quota period is a UTC month). */
    public static String resetDate(Instant resetAt) {
        return resetAt == null ? null : resetAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
