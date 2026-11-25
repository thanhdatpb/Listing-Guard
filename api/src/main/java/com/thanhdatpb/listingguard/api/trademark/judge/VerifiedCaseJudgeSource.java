package com.thanhdatpb.listingguard.api.trademark.judge;

import com.thanhdatpb.listingguard.api.review.VerifiedCases;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.VerifiedState;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * This client's own verified decisions for the evidence pack (ADR-051, ADR-044). Every statement starts with the client's id
 * (ADR-007), and it answers only while the verified cases are switched on ({@code listing-guard.verified-cases.enabled}) and only with
 * a case that is neither revoked nor expired. It reads the outcome and the date of the decision, and the field kind and the mark of the
 * finding the decision was about; never the reason, a note, the reviewer or the listing. The mark of the decided finding is read
 * from the retained check result, so a decision whose result has been deleted by retention (30 days, shorter than the 90 days of a
 * verified case) can no longer be matched by mark and is not used: the evidence then says {@code none}, not a guess.
 *
 * <p>{@code RESOLVED} (a fix was confirmed) is shown as {@code confirmed}: the finding was real. {@code DISMISSED} (no action
 * required) is shown as {@code false_alarm}.
 */
@Component
public class VerifiedCaseJudgeSource implements JudgeEvidenceGatherer.ReviewSource {

    private static final Logger log = LoggerFactory.getLogger(VerifiedCaseJudgeSource.class);

    private final VerifiedCases verifiedCases;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public VerifiedCaseJudgeSource(VerifiedCases verifiedCases, JdbcTemplate jdbc, Clock clock) {
        this.verifiedCases = verifiedCases;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public VerifiedState nearest(UUID clientId, String marketplace, String ruleId, String fieldKind, String mark) {
        if (!verifiedCases.enabled()) {
            return VerifiedState.NOT_KNOWN;
        }
        Instant now = clock.instant();
        try {
            List<VerifiedState> found = jdbc.query("""
                    SELECT v.outcome, v.verified_at
                    FROM verified_case v
                    JOIN review_case rc ON rc.client_id = v.client_id AND rc.id = v.case_id
                    JOIN trademark_check c ON c.client_id = rc.client_id AND c.id = rc.check_id AND c.expires_at > ?
                    JOIN trademark_check_finding f ON f.check_id = c.id AND f.position = rc.finding_position
                    WHERE v.client_id = ? AND v.marketplace_code = ? AND v.pack_id = 'trademark-risk' AND v.rule_id = ?
                          AND v.evidence_source = 'CATALOG' AND v.metadata_version = 1 AND v.revoked_at IS NULL AND v.expires_at > ?
                          AND regexp_replace(f.field, '\\[[0-9]+\\]', '', 'g') = ? AND lower(f.matched_mark) = ?
                    ORDER BY v.verified_at DESC, v.case_id
                    LIMIT 1""",
                    (rs, i) -> new VerifiedState("RESOLVED".equals(rs.getString("outcome"))
                            ? VerifiedState.Kind.CONFIRMED : VerifiedState.Kind.FALSE_ALARM,
                            Math.max(0, ChronoUnit.DAYS.between(rs.getTimestamp("verified_at").toInstant(), now))),
                    Timestamp.from(now), clientId, marketplace, ruleId, Timestamp.from(now), fieldKind, mark);
            return found.isEmpty() ? VerifiedState.NO_CASE : found.getFirst();
        } catch (DataAccessException e) {
            log.warn("The verified decisions for the judge's evidence could not be read; they are left out: {}",
                    e.getClass().getSimpleName());
            return VerifiedState.NOT_KNOWN;
        }
    }

    @Override
    public String digest(UUID clientId) {
        if (!verifiedCases.enabled()) {
            return "";
        }
        Instant now = clock.instant();
        try {
            return jdbc.queryForObject("""
                    SELECT count(*) || ':' || coalesce(extract(epoch FROM max(verified_at))::bigint::text, '0')
                    FROM verified_case
                    WHERE client_id = ? AND pack_id = 'trademark-risk' AND evidence_source = 'CATALOG' AND metadata_version = 1
                          AND revoked_at IS NULL AND expires_at > ?""", String.class, clientId, Timestamp.from(now));
        } catch (DataAccessException e) {
            log.warn("The digest of the verified decisions could not be read; the evidence is left out: {}", e.getClass().getSimpleName());
            return "";
        }
    }
}
