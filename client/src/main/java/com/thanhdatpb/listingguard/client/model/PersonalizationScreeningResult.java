package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer of {@code POST /api/v1/personalization-screenings} (schema {@code PersonalizationScreeningResult}): the character
 * rules of the print profile in {@code findings} and the trademark scan in {@code trademark}. Show both; neither replaces the
 * other. A reference warning: show {@code disclaimer}.
 *
 * @param checkId         the stored result's id
 * @param marketplace     the marketplace code
 * @param rulesetVersion  the ruleset that ran
 * @param analysisVersion what decided the findings
 * @param printProfile    the print profile used
 * @param cached          true when a stored complete result was returned
 * @param completeness    whether every rule ran ({@code INCOMPLETE} when no trademark catalog is configured)
 * @param failedRuleIds   the rules that failed
 * @param disclaimer      the text to show with the result
 * @param createdAt       when the result was stored (UTC)
 * @param expiresAt       when it stops being retained (UTC)
 * @param summary         the number of character findings per severity
 * @param findings        the character findings
 * @param trademark       the trademark scan of the values
 */
public record PersonalizationScreeningResult(
        UUID checkId,
        String marketplace,
        String rulesetVersion,
        String analysisVersion,
        String printProfile,
        boolean cached,
        Completeness completeness,
        List<String> failedRuleIds,
        String disclaimer,
        Instant createdAt,
        Instant expiresAt,
        Summary summary,
        List<CharacterFinding> findings,
        PersonalizationTrademark trademark) {

    public PersonalizationScreeningResult {
        failedRuleIds = Copies.list(failedRuleIds);
        findings = Copies.list(findings);
    }
}
