package com.thanhdatpb.listingguard.ruleengine.readiness;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import java.util.EnumMap;
import java.util.Map;

/**
 * Turns the result of one rule run into a {@link ReadinessScore} (ADR-014). The formula and its numbers come from a
 * {@link ReadinessConfig}; none is written here.
 *
 * <p>An incomplete run (a rule failed) has no score and is not ready, because a missing finding may be a rule that did
 * not run. A listing with an ERROR is not ready and its score is capped at {@code not_ready_max_score}.
 */
public final class ReadinessScorer {

    private final ReadinessConfig config;

    public ReadinessScorer(ReadinessConfig config) {
        this.config = config;
    }

    public ReadinessConfig config() {
        return config;
    }

    /** @throws IllegalArgumentException if the run is of another pack than the one the config scores */
    public ReadinessScore score(RuleRun run) {
        if (!config.packId().equals(run.packId())) {
            throw new IllegalArgumentException("Readiness " + config.version() + " scores pack '" + config.packId()
                    + "', not '" + run.packId() + "'");
        }
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (Finding finding : run.findings()) {
            counts.merge(finding.severity(), 1, Integer::sum);
        }
        int errors = counts.get(Severity.ERROR);
        int warnings = counts.get(Severity.WARNING);
        int infos = counts.get(Severity.INFO);

        if (!run.complete()) {
            return new ReadinessScore(config.version(), config.packId(), ReadinessScore.Status.INCOMPLETE, null, false,
                    errors, warnings, infos, run.failedRuleIds());
        }
        long taken = 0;
        for (Severity severity : Severity.values()) {
            taken += (long) config.penalty(severity) * counts.get(severity);
        }
        int raw = (int) Math.max(0, config.baseScore() - taken);
        boolean ready = errors == 0;
        int score = ready ? raw : Math.min(raw, config.notReadyMaxScore());
        return new ReadinessScore(config.version(), config.packId(), ReadinessScore.Status.SCORED, score, ready,
                errors, warnings, infos, run.failedRuleIds());
    }
}
