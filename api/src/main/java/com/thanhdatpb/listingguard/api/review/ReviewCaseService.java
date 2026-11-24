package com.thanhdatpb.listingguard.api.review;

import io.swagger.v3.oas.annotations.media.Schema;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.feedback.FeedbackFindings;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewCaseService {
    private static final String SELECT_CASE = "SELECT * FROM review_case WHERE client_id = ?";
    private static final String SELECT_EVENT = "SELECT * FROM review_case_event WHERE client_id = ? AND case_id = ?";

    public enum Sort { NEWEST, OLDEST }

    public record Page(List<ReviewCase> items, @Schema(nullable = true) String nextCursor) { }
    public record Detail(ReviewCase reviewCase, boolean evidenceAvailable) { }
    public record AuditPage(List<ReviewAuditEvent> items, @Schema(nullable = true) Integer nextAfterRevision) { }

    private enum ActorKind { API_KEY, CONSOLE_ACCOUNT }
    private record Replay(ReviewAuditEvent event, ActorKind kind) { }
    public record Counts(long open, long resolved) { }

    public record Completion(long total, long completed, long resolved, Long meanResolutionMinutes) { }

    private final JdbcTemplate jdbc;
    private final FeedbackFindings findings;

    public ReviewCaseService(JdbcTemplate jdbc, FeedbackFindings findings) {
        this.jdbc = jdbc;
        this.findings = findings;
    }

    @Transactional(readOnly = true)
    public Page list(UUID client, ReviewCaseStatus status, ReviewCase.Marketplace marketplace,
                     ReviewCase.Severity severity, OffsetDateTime createdBefore, OffsetDateTime createdAfter,
                     Sort sort, int limit, String cursor) {
        validateLimit(limit);
        validateDate(createdBefore);
        validateDate(createdAfter);
        if (createdBefore != null && createdAfter != null && !createdAfter.isBefore(createdBefore)) {
            throw invalidQuery();
        }
        String scope = String.join("|", "v1", client.toString(), String.valueOf(status), String.valueOf(marketplace),
                String.valueOf(severity), sort.name(), String.valueOf(createdBefore), String.valueOf(createdAfter));
        StringBuilder sql = new StringBuilder(SELECT_CASE);
        List<Object> params = new ArrayList<>();
        params.add(client);
        filter(sql, params, "status", status);
        filter(sql, params, "marketplace_code", marketplace);
        filter(sql, params, "severity", severity);
        if (createdBefore != null) {
            sql.append(" AND created_at < ?");
            params.add(createdBefore);
        }
        if (createdAfter != null) {
            sql.append(" AND created_at >= ?");
            params.add(createdAfter);
        }
        if (cursor != null) {
            ReviewCursor position = ReviewCursor.decode(cursor, scope);
            sql.append(sort == Sort.NEWEST ? " AND (created_at, id) < (?, ?)" : " AND (created_at, id) > (?, ?)");
            params.add(position.createdAt());
            params.add(position.id());
        }
        sql.append(sort == Sort.NEWEST ? " ORDER BY created_at DESC, id DESC" : " ORDER BY created_at ASC, id ASC");
        sql.append(" LIMIT ?");
        params.add(limit + 1);
        List<ReviewCase> cases = jdbc.query(sql.toString(), ReviewCaseService::readCase, params.toArray());
        List<ReviewCase> page = List.copyOf(cases.subList(0, Math.min(limit, cases.size())));
        String next = null;
        if (cases.size() > limit) {
            ReviewCase last = page.getLast();
            next = new ReviewCursor(last.createdAt(), last.id()).encode(scope);
        }
        return new Page(page, next);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID client, UUID id) {
        ReviewCase current = find(client, id, false);
        return new Detail(current, findings.resolve(client, target(current)).isPresent());
    }

    @Transactional
    public ReviewCase create(AuthenticatedClient client, FeedbackTarget target) {
        return create(client.id(), ActorKind.API_KEY, requireWriter(client), target);
    }

    @Transactional
    public ReviewCase createInConsole(ConsoleUser user, FeedbackTarget target) {
        return create(user.clientId(), ActorKind.CONSOLE_ACCOUNT, requireConsoleReviewer(user), target);
    }

    private ReviewCase create(UUID clientId, ActorKind actorKind, UUID actor, FeedbackTarget target) {
        ReviewCase existing = byTarget(clientId, target);
        if (existing != null) {
            return existing;
        }
        FeedbackFindings.Resolved finding = findings.resolve(clientId, target).orElseThrow(() ->
                new ReviewCaseException(HttpStatus.NOT_FOUND, "finding-not-found", "No retained finding at that address."));
        List<ReviewCase> inserted = jdbc.query("""
                INSERT INTO review_case
                    (client_id, id, pack_id, check_id, finding_position, marketplace_code, severity, status, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', 0)
                ON CONFLICT (client_id, pack_id, check_id, finding_position) DO NOTHING
                RETURNING *
                """, ReviewCaseService::readCase, clientId, UUID.randomUUID(), target.pack(), target.checkId(),
                target.position(), finding.marketplace(), finding.level());
        if (inserted.isEmpty()) {
            return byTarget(clientId, target);
        }
        ReviewCase created = inserted.getFirst();
        append(clientId, created.id(), actorKind, new ReviewAuditEvent(0, null, actor, null, ReviewCaseStatus.OPEN, null,
                created.createdAt()));
        return created;
    }

    @Transactional
    public ReviewAuditEvent decide(AuthenticatedClient client, UUID id, ReviewDecision decision) {
        return decide(client.id(), id, decision, ActorKind.API_KEY, () -> requireWriter(client));
    }

    @Transactional
    public ReviewAuditEvent decideInConsole(ConsoleUser user, UUID id, ReviewDecision decision) {
        return decide(user.clientId(), id, decision, ActorKind.CONSOLE_ACCOUNT, () -> requireConsoleReviewer(user));
    }

    UUID requireConsoleReviewer(ConsoleUser user) {
        return consoleReviewer(user, true).orElseThrow(() ->
                new ReviewCaseException(HttpStatus.FORBIDDEN, "review-write-forbidden",
                        "This Console account is not authorized to write review cases."));
    }

    void requireConsoleReviewerForRead(ConsoleUser user) {
        if (!canReviewInConsole(user)) {
            throw new ReviewCaseException(HttpStatus.FORBIDDEN, "review-read-forbidden",
                    "This Console account is not authorized to read verified cases.");
        }
    }

    public boolean canReviewInConsole(ConsoleUser user) {
        return consoleReviewer(user, false).isPresent();
    }

    private Optional<UUID> consoleReviewer(ConsoleUser user, boolean lock) {
        return jdbc.query("""
                SELECT a.id FROM account a
                JOIN console_review_grant g ON g.account_id = a.id AND g.client_id = a.client_id
                JOIN client c ON c.id = a.client_id
                WHERE a.client_id = ? AND a.email = ? AND c.active
                """ + (lock ? " FOR SHARE OF a, g, c" : ""),
                (rs, row) -> rs.getObject(1, UUID.class), user.clientId(), user.email()).stream().findFirst();
    }

    public Counts counts(UUID client) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'OPEN'), count(*) FILTER (WHERE status = 'RESOLVED')
                FROM review_case WHERE client_id = ? AND status IN ('OPEN', 'RESOLVED')
                """, (rs, row) -> new Counts(rs.getLong(1), rs.getLong(2)), client);
    }

    public Completion completion(UUID client) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status IN ('RESOLVED', 'DISMISSED')) AS completed,
                       count(*) FILTER (WHERE status = 'RESOLVED') AS resolved,
                       round(avg(extract(epoch FROM updated_at - created_at) / 60)
                             FILTER (WHERE status = 'RESOLVED'))::bigint AS resolution_minutes
                FROM review_case WHERE client_id = ?
                """, (rs, row) -> new Completion(rs.getLong("total"), rs.getLong("completed"), rs.getLong("resolved"),
                rs.getObject("resolution_minutes", Long.class)), client);
    }

    public FeedbackFindings.Resolved retainedEvidence(UUID client, UUID id) {
        return findings.resolve(client, target(find(client, id, false))).orElseThrow(() ->
                new ReviewCaseException(HttpStatus.NOT_FOUND, "finding-not-found", "No retained finding at that address."));
    }

    private ReviewAuditEvent decide(UUID client, UUID id, ReviewDecision decision, ActorKind kind, Supplier<UUID> reviewer) {
        // Check ownership before authorization, and hold the live grant while committing the decision.
        find(client, id, false);
        UUID actor = reviewer.get();
        ReviewCase current = find(client, id, true);
        List<Replay> replay = jdbc.query(SELECT_EVENT + " AND request_id = ?",
                (rs, row) -> new Replay(readEvent(rs, row), ActorKind.valueOf(rs.getString("actor_kind"))),
                client, id, decision.requestId());
        if (!replay.isEmpty()) {
            ReviewAuditEvent event = replay.getFirst().event();
            if (replay.getFirst().kind() == kind && event.actorId().equals(actor) && event.status() == decision.status()
                    && event.reason() == decision.reason() && event.revision() - 1 == decision.expectedRevision()) {
                return event;
            }
            throw conflict("review-request-conflict", "This request ID is already in use.");
        }
        if (current.revision() != decision.expectedRevision()) {
            throw conflict("review-revision-conflict", "The review case revision has changed.");
        }
        if (!current.status().permits(decision.status())) {
            throw conflict("review-invalid-transition", "The requested review transition is not allowed.");
        }
        if (decision.status().requiredReason() != decision.reason()) {
            throw new ReviewCaseException(HttpStatus.BAD_REQUEST, "review-invalid-reason",
                    "The reason is not allowed for the requested status.");
        }
        if (findings.resolve(client, target(current)).isEmpty()) {
            throw conflict("review-evidence-unavailable", "The review case evidence is no longer available.");
        }

        int revision = current.revision() + 1;
        OffsetDateTime occurredAt = jdbc.queryForObject("""
                UPDATE review_case
                SET status = ?, reviewer_id = ?, reason = ?, revision = ?,
                    updated_at = GREATEST(updated_at, clock_timestamp())
                WHERE client_id = ? AND id = ? AND revision = ?
                RETURNING updated_at
                """, OffsetDateTime.class, decision.status().name(), actor, name(decision.reason()), revision,
                client, id, current.revision());
        ReviewAuditEvent event = new ReviewAuditEvent(revision, decision.requestId(), actor,
                current.status(), decision.status(), decision.reason(), occurredAt);
        append(client, id, kind, event);
        return event;
    }

    @Transactional(readOnly = true)
    public AuditPage audit(UUID client, UUID id, int afterRevision, int limit) {
        find(client, id, false);
        validateLimit(limit);
        if (afterRevision < -1) {
            throw invalidQuery();
        }
        List<ReviewAuditEvent> events = jdbc.query(SELECT_EVENT + " AND revision > ? ORDER BY revision LIMIT ?",
                ReviewCaseService::readEvent, client, id, afterRevision, limit + 1);
        List<ReviewAuditEvent> page = List.copyOf(events.subList(0, Math.min(limit, events.size())));
        return new AuditPage(page, events.size() > limit ? page.getLast().revision() : null);
    }

    private UUID requireWriter(AuthenticatedClient client) {
        // Recheck even if authentication was cached. A grant can be revoked without changing the API key.
        return jdbc.query("""
                SELECT k.id FROM api_key k
                JOIN review_case_writer g ON g.api_key_id = k.id
                JOIN client c ON c.id = k.client_id
                WHERE k.client_id = ? AND k.key_id = ? AND k.active AND k.revoked_at IS NULL AND c.active
                FOR SHARE OF k, g, c
                """, (rs, row) -> rs.getObject(1, UUID.class), client.id(), client.keyId()).stream().findFirst()
                .orElseThrow(() -> new ReviewCaseException(HttpStatus.FORBIDDEN, "review-write-forbidden",
                        "This API key is not authorized to write review cases."));
    }

    private ReviewCase byTarget(UUID client, FeedbackTarget target) {
        return jdbc.query(SELECT_CASE + " AND pack_id = ? AND check_id = ? AND finding_position = ?",
                ReviewCaseService::readCase, client, target.pack(), target.checkId(), target.position()).stream()
                .findFirst().orElse(null);
    }

    private ReviewCase find(UUID client, UUID id, boolean lock) {
        return jdbc.query(SELECT_CASE + " AND id = ?" + (lock ? " FOR UPDATE" : ""),
                ReviewCaseService::readCase, client, id).stream().findFirst().orElseThrow(() ->
                new ReviewCaseException(HttpStatus.NOT_FOUND, "review-case-not-found", "No review case at that address."));
    }

    private void append(UUID client, UUID id, ActorKind kind, ReviewAuditEvent event) {
        jdbc.update("""
                INSERT INTO review_case_event
                    (client_id, case_id, revision, request_id, actor_id, previous_status, status, reason, occurred_at, actor_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, client, id, event.revision(), event.requestId(), event.actorId(),
                name(event.previousStatus()), event.status().name(), name(event.reason()), event.occurredAt(), kind.name());
    }

    private static ReviewCase readCase(ResultSet rs, int row) throws SQLException {
        return new ReviewCase(rs.getObject("id", UUID.class), rs.getString("pack_id"),
                rs.getObject("check_id", UUID.class), rs.getInt("finding_position"),
                ReviewCase.Marketplace.valueOf(rs.getString("marketplace_code")),
                ReviewCase.Severity.valueOf(rs.getString("severity")), ReviewCaseStatus.valueOf(rs.getString("status")),
                rs.getInt("revision"), rs.getObject("reviewer_id", UUID.class),
                readEnum(rs, "reason", ReviewDecision.Reason.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static ReviewAuditEvent readEvent(ResultSet rs, int row) throws SQLException {
        return new ReviewAuditEvent(rs.getInt("revision"), rs.getObject("request_id", UUID.class),
                rs.getObject("actor_id", UUID.class), readEnum(rs, "previous_status", ReviewCaseStatus.class),
                ReviewCaseStatus.valueOf(rs.getString("status")), readEnum(rs, "reason", ReviewDecision.Reason.class),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }

    private static <T extends Enum<T>> T readEnum(ResultSet rs, String column, Class<T> type) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FeedbackTarget target(ReviewCase current) {
        return new FeedbackTarget(current.pack(), current.checkId(), current.findingIndex());
    }

    private static void filter(StringBuilder sql, List<Object> params, String column, Enum<?> value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            params.add(value.name());
        }
    }

    private static void validateDate(OffsetDateTime date) {
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) {
            throw invalidQuery();
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw invalidQuery();
        }
    }

    static ReviewCaseException invalidQuery() {
        return new ReviewCaseException(HttpStatus.BAD_REQUEST, "review-invalid-page", "Invalid review pagination parameters.");
    }

    private static ReviewCaseException conflict(String code, String detail) {
        return new ReviewCaseException(HttpStatus.CONFLICT, code, detail);
    }
}
