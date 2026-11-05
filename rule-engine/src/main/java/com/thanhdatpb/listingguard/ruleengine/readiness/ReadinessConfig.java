package com.thanhdatpb.listingguard.ruleengine.readiness;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * The settings of one publish readiness formula (ADR-014), parsed from {@code readiness/<version>.yaml}.
 * {@code contentHash} is the SHA-256 of the normalized YAML text. The constructor checks the numbers so a bad file
 * fails when it is loaded, not when a listing is scored.
 *
 * @param packId           the rule pack whose findings are scored; a run of another pack is refused
 * @param baseScore        the score of a listing with no findings, 1 to 100
 * @param penalties        points taken off per finding of each severity, never negative, every severity present
 * @param notReadyMaxScore the highest score a listing that is not ready can have, 0 up to {@code baseScore}
 */
public record ReadinessConfig(
        String version,
        LocalDate checkedAt,
        String contentHash,
        String packId,
        int baseScore,
        Map<Severity, Integer> penalties,
        int notReadyMaxScore) {

    public ReadinessConfig {
        if (version == null || version.isBlank() || packId == null || packId.isBlank()) {
            throw new RulesetException("A readiness config needs a version and a pack_id");
        }
        if (baseScore < 1 || baseScore > 100) {
            throw new RulesetException("base_score must be between 1 and 100, got " + baseScore);
        }
        EnumMap<Severity, Integer> copy = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            Integer penalty = penalties == null ? null : penalties.get(severity);
            if (penalty == null || penalty < 0) {
                throw new RulesetException("penalties." + severity + " must be a number of 0 or more");
            }
            copy.put(severity, penalty);
        }
        penalties = Map.copyOf(copy);
        if (notReadyMaxScore < 0 || notReadyMaxScore > baseScore) {
            throw new RulesetException("not_ready_max_score must be between 0 and base_score, got " + notReadyMaxScore);
        }
    }

    public int penalty(Severity severity) {
        return penalties.get(severity);
    }
}
