package com.thanhdatpb.listingguard.api.review;

import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifiedCases {
    static final int LIMIT = 5;
    private static final String CASE = "SELECT * FROM verified_case WHERE client_id = ? AND case_id = ?";

    public record Snapshot(UUID caseId, String ruleId, String severity, String analysisVersion, String rulesetVersion,
                           String catalogVersion, String productType, ReviewCaseStatus outcome, String reason,
                           int decisionRevision, int metadataVersion, UUID verifiedBy,
                           OffsetDateTime verifiedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt) { }
    public record Event(int revision, String action, UUID actorId, OffsetDateTime occurredAt) { }
    public record Match(Snapshot snapshot, long ageDays, boolean versionDiffers, boolean severityDiffers,
                        boolean productTypeDiffers) { }
    public record View(Snapshot snapshot, boolean expired, List<Event> events, List<Match> matches) { }

    private final ReviewCaseService reviews;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public VerifiedCases(ReviewCaseService reviews, JdbcTemplate jdbc, Clock clock,
                         @Value("${listing-guard.verified-cases.enabled:false}") boolean enabled) {
        this.reviews = reviews;
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @Transactional
    public void verify(ConsoleUser user, UUID id) {
        requireEnabled();
        var detail = reviews.get(user.clientId(), id);
        UUID actor = reviews.requireConsoleReviewer(user);
        if (find(user.clientId(), id) != null) {
            return;
        }
        ReviewCase item = detail.reviewCase();
        if (item.status() != ReviewCaseStatus.RESOLVED && item.status() != ReviewCaseStatus.DISMISSED) {
            throw failure(HttpStatus.CONFLICT, "verified-case-open", "Only a completed review can be verified.");
        }
        var evidence = reviews.retainedEvidence(user.clientId(), id);
        if (!item.marketplace().name().equals(evidence.marketplace()) || !item.severity().name().equals(evidence.level())
                || evidence.ruleId() == null || evidence.ruleId().isBlank()
                || evidence.analysisVersion().isBlank() || evidence.rulesetVersion().isBlank()) {
            throw failure(HttpStatus.CONFLICT, "verified-case-provenance", "The retained finding does not match this review.");
        }
        OffsetDateTime at = now();
        int created = jdbc.update("""
                INSERT INTO verified_case (client_id, case_id, pack_id, marketplace_code, rule_id, severity,
                    evidence_source, analysis_version, ruleset_version, catalog_version, product_type, outcome, reason,
                    decision_revision, metadata_version, verified_by, verified_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (client_id, case_id) DO NOTHING
                """, user.clientId(), id, item.pack(), evidence.marketplace(), evidence.ruleId(), evidence.level(),
                evidence.source().name(), evidence.analysisVersion(), evidence.rulesetVersion(), evidence.catalogVersion(),
                evidence.productType(), item.status().name(), item.reason().name(), item.revision(), actor, at, at.plusDays(90));
        if (created == 1) {
            event(user.clientId(), id, 1, "VERIFIED", actor, at);
        }
    }

    @Transactional
    public void revoke(ConsoleUser user, UUID id) {
        requireEnabled();
        reviews.get(user.clientId(), id);
        UUID actor = reviews.requireConsoleReviewer(user);
        OffsetDateTime at = now();
        var revoked = jdbc.query("""
                UPDATE verified_case SET revoked_at = GREATEST(verified_at, ?)
                WHERE client_id = ? AND case_id = ? AND revoked_at IS NULL RETURNING revoked_at
                """, (rs, row) -> rs.getObject(1, OffsetDateTime.class), at, user.clientId(), id);
        if (!revoked.isEmpty()) {
            event(user.clientId(), id, 2, "REVOKED", actor, revoked.getFirst());
        } else if (find(user.clientId(), id) == null) {
            throw failure(HttpStatus.NOT_FOUND, "verified-case-not-found", "No verified case at that address.");
        }
    }

    @Transactional(readOnly = true)
    public View view(ConsoleUser user, UUID id) {
        requireEnabled();
        reviews.requireConsoleReviewerForRead(user);
        // Re-resolve the reference by tenant before reading either the record or recommendations.
        var owned = reviews.get(user.clientId(), id);
        Snapshot snapshot = find(user.clientId(), id);
        List<Event> audit = snapshot == null ? List.of() : jdbc.query("""
                SELECT revision, action, actor_id, occurred_at FROM verified_case_event
                WHERE client_id = ? AND case_id = ? ORDER BY revision
                """, (rs, row) -> new Event(rs.getInt(1), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getObject(4, OffsetDateTime.class)), user.clientId(), id);
        OffsetDateTime at = now();
        if (!owned.evidenceAvailable()) {
            return new View(snapshot, snapshot != null && !snapshot.expiresAt().isAfter(at), audit, null);
        }
        var evidence = reviews.retainedEvidence(user.clientId(), id);
        jdbc.execute("SET LOCAL statement_timeout = '1500ms'");
        List<Match> matches = jdbc.query("""
                WITH candidates AS (
                    SELECT * FROM verified_case
                    WHERE client_id = ? AND marketplace_code = ? AND pack_id = ? AND rule_id = ?
                          AND evidence_source = ? AND case_id <> ? AND revoked_at IS NULL AND expires_at > ?
                          AND metadata_version = 1
                          AND (CAST(? AS varchar) IS NULL OR product_type IS NULL OR product_type = ?)
                    ORDER BY verified_at DESC, case_id
                    LIMIT 200
                )
                SELECT * FROM candidates
                ORDER BY (analysis_version = ?) DESC,
                         CASE WHEN product_type = ? THEN 1 ELSE 0 END DESC,
                         (severity = ?) DESC, verified_at DESC, case_id
                LIMIT ?
                """, (rs, row) -> {
            Snapshot match = read(rs);
            return new Match(match, Math.max(0, ChronoUnit.DAYS.between(match.verifiedAt(), at)),
                    !match.analysisVersion().equals(evidence.analysisVersion())
                            || !Objects.equals(match.rulesetVersion(), evidence.rulesetVersion())
                            || !Objects.equals(match.catalogVersion(), evidence.catalogVersion()),
                    !match.severity().equals(evidence.level()), !Objects.equals(match.productType(), evidence.productType()));
        }, user.clientId(), evidence.marketplace(), owned.reviewCase().pack(), evidence.ruleId(), evidence.source().name(), id, at,
                evidence.productType(), evidence.productType(), evidence.analysisVersion(), evidence.productType(),
                evidence.level(), LIMIT);
        return new View(snapshot, snapshot != null && !snapshot.expiresAt().isAfter(at), audit, List.copyOf(matches));
    }

    private Snapshot find(UUID client, UUID id) {
        return jdbc.query(CASE, (rs, row) -> read(rs), client, id).stream().findFirst().orElse(null);
    }

    private static Snapshot read(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getObject("case_id", UUID.class), rs.getString("rule_id"), rs.getString("severity"),
                rs.getString("analysis_version"), rs.getString("ruleset_version"), rs.getString("catalog_version"),
                rs.getString("product_type"), ReviewCaseStatus.valueOf(rs.getString("outcome")), rs.getString("reason"),
                rs.getInt("decision_revision"), rs.getInt("metadata_version"), rs.getObject("verified_by", UUID.class),
                rs.getObject("verified_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }

    private void event(UUID client, UUID id, int revision, String action, UUID actor, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO verified_case_event (client_id, case_id, revision, action, actor_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, client, id, revision, action, actor, at);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw failure(HttpStatus.NOT_FOUND, "verified-cases-disabled", "Verified cases are unavailable.");
        }
    }

    private static ReviewCaseException failure(HttpStatus status, String code, String detail) {
        return new ReviewCaseException(status, code, detail);
    }
}
