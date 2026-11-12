package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer of {@code POST /api/v1/checks} (schema {@code CheckResult}). An empty {@code findings} list means these rules found
 * nothing, not that the marketplace accepts the listing.
 *
 * @param checkId         the stored check's id
 * @param marketplace     the marketplace code
 * @param rulesetVersion  the ruleset that ran
 * @param analysisVersion what decided the findings; part of the cache identity
 * @param contentHash     the hash of the normalized listing
 * @param status          the check's status
 * @param cached          true when the stored result of an identical earlier check was returned
 * @param createdAt       when that check was first stored (UTC)
 * @param summary         the number of findings per severity
 * @param findings        one entry per problem
 * @param fixesComputed   whether fix suggestions were computed; {@code null} on a deployment without them
 * @param readiness       publish readiness of the listing-rules pack, or {@code null}; not a trademark verdict
 */
public record CheckResult(
        UUID checkId,
        String marketplace,
        String rulesetVersion,
        String analysisVersion,
        String contentHash,
        CheckStatus status,
        boolean cached,
        Instant createdAt,
        Summary summary,
        List<Finding> findings,
        Boolean fixesComputed,
        Readiness readiness) {

    public CheckResult {
        findings = Copies.list(findings);
    }
}
