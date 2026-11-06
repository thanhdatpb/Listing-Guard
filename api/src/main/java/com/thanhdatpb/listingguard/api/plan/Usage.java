package com.thanhdatpb.listingguard.api.plan;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What {@code GET /api/v1/usage} returns: the client's plan, its quota, how much of this UTC month is used and when it
 * resets (ADR-013).
 */
public record Usage(
        @Schema(description = "Whether plans are on in this deployment. When false the quota and the packs are not applied: "
                + "`monthlyQuota` and `remaining` are null and `packs` lists every pack.")
        boolean enforced,
        @Schema(description = "The client's plan code: FREE, SELLER or PRO.", example = "SELLER")
        String plan,
        @Schema(description = "Results the plan allows per UTC month; null means unlimited. One unit is one new result that is stored, "
                + "per endpoint: a cached result costs nothing, and a full check of one listing (rules and trademark) is two units.",
                nullable = true, example = "1000")
        Integer monthlyQuota,
        @Schema(description = "Units used so far in the current UTC month.", example = "42")
        long used,
        @Schema(description = "Units left this month; null when the quota is unlimited or not applied.", nullable = true, example = "958")
        Long remaining,
        @Schema(description = "First day of the current UTC month.", example = "2026-11-01")
        LocalDate periodStart,
        @Schema(description = "The instant the quota resets: the start of the next UTC month.", example = "2026-12-01T00:00:00Z")
        Instant resetAt,
        @Schema(description = "The packs the client may use.", example = "[\"batch\",\"listing-rules\",\"sheet-connector\",\"trademark-risk\",\"variation-checks\"]")
        List<String> packs) {
}
