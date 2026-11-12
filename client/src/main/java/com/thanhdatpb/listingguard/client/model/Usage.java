package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The answer of {@code GET /api/v1/usage} (schema {@code Usage}): the calling client's plan and its position this month.
 *
 * @param plan         the plan code, for example {@code FREE}
 * @param packs        the packs the plan includes
 * @param enforced     whether plans are applied on this deployment
 * @param monthlyQuota the monthly quota, or {@code null} for unlimited
 * @param used         units used this month
 * @param remaining    units left, or {@code null} for unlimited
 * @param periodStart  the first day of the month counted (UTC)
 * @param resetAt      when the count starts again (UTC)
 */
public record Usage(
        String plan,
        List<String> packs,
        boolean enforced,
        Integer monthlyQuota,
        long used,
        Long remaining,
        LocalDate periodStart,
        Instant resetAt) {

    public Usage {
        packs = Copies.list(packs);
    }
}
