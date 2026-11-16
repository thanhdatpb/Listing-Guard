package com.thanhdatpb.listingguard.api.rewrite;

import com.thanhdatpb.listingguard.api.feedback.FeedbackFindings;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores one row per finished run of the rewrite button and records that the seller copied a suggestion (ADR-037, V40). The row has
 * counts and an address only: no title, tag, prompt, answer or note. The finding the run started from is resolved by the same
 * client-scoped lookup as a verdict of ADR-034, and its rule id and level are copied, so a finding of another client is never read.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.llm.rewrite", name = "enabled", havingValue = "true")
public class RewriteRunStore {

    /**
     * What the statistics page shows for a period: runs, runs with at least one safe suggestion, and runs where one was copied.
     * The use-rate is {@code used / withSafe}; it is {@code null} when no run had a safe suggestion, never zero.
     */
    public record Usage(int runs, int withSafe, int used) {

        public Double rate() {
            return withSafe == 0 ? null : (double) used / withSafe;
        }
    }

    private final JdbcTemplate jdbc;
    private final FeedbackFindings findings;
    private final Clock clock;

    public RewriteRunStore(JdbcTemplate jdbc, FeedbackFindings findings, Clock clock) {
        this.jdbc = jdbc;
        this.findings = findings;
        this.clock = clock;
    }

    /**
     * @param account the console account, or null
     * @param anchor  the finding the run started from, or null; one that does not belong to the client is stored as no anchor
     * @return the id of the stored run
     */
    public UUID record(UUID clientId, UUID account, String marketplace, String modelId, int safe, FeedbackTarget anchor) {
        UUID id = UUID.randomUUID();
        Optional<FeedbackFindings.Resolved> resolved = anchor == null ? Optional.empty() : findings.resolve(clientId, anchor);
        jdbc.update("""
                INSERT INTO rewrite_suggestion_run (id, client_id, account_id, marketplace_code, model_id, anchor_pack, anchor_check_id,
                    anchor_position, anchor_rule_id, anchor_level, candidates_safe, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, clientId, account, marketplace.toUpperCase(Locale.ROOT), modelId,
                resolved.isPresent() ? anchor.pack() : null, resolved.isPresent() ? anchor.checkId() : null,
                resolved.isPresent() ? anchor.position() : null, resolved.map(FeedbackFindings.Resolved::ruleId).orElse(null),
                resolved.map(FeedbackFindings.Resolved::level).orElse(null), safe, Timestamp.from(clock.instant()));
        return id;
    }

    /**
     * The seller copied suggestion {@code candidate} of a run of this client. The first copy of a run counts; later ones change
     * nothing. Returns false for a run of another client, an unknown run, or a number the run did not offer: the caller answers all
     * three alike (ADR-007).
     */
    public boolean markUsed(UUID clientId, UUID runId, int candidate) {
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("""
                UPDATE rewrite_suggestion_run
                SET used_at = COALESCE(used_at, ?), used_candidate = COALESCE(used_candidate, ?)
                WHERE id = ? AND client_id = ? AND ? BETWEEN 1 AND candidates_safe""", now, candidate, runId, clientId, candidate);
        return changed == 1;
    }

    public Usage usage(UUID clientId, Instant since) {
        return jdbc.queryForObject("""
                SELECT count(*) AS runs, count(*) FILTER (WHERE candidates_safe > 0) AS with_safe,
                       count(*) FILTER (WHERE used_at IS NOT NULL) AS used
                FROM rewrite_suggestion_run WHERE client_id = ? AND created_at >= ?""",
                (rs, i) -> new Usage(rs.getInt("runs"), rs.getInt("with_safe"), rs.getInt("used")),
                clientId, Timestamp.from(since));
    }
}
