package com.thanhdatpb.listingguard.api.feedback;

import com.thanhdatpb.listingguard.api.plan.Pack;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns the address of a finding into what a verdict keeps of it (ADR-034), for the calling client only. Every statement starts
 * with {@code c.client_id = ?} (ADR-007), the retained packs answer only while {@code expires_at} is in the future (ADR-009), and
 * nothing that holds text of the listing is selected: no value, message, suggestion, field or snippet.
 */
@Component
public class FeedbackFindings {

    /** What a verdict copies of its finding when it is given, so the row keeps its meaning after the result is gone. */
    public record Resolved(String marketplace, String analysisVersion, String ruleId, FeedbackSource source, String level) {
    }

    private final JdbcTemplate jdbc;

    public FeedbackFindings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Resolved> resolve(UUID clientId, FeedbackTarget target) {
        List<Resolved> rows;
        if (target.pack().equals(Pack.LISTING_RULES.id())) {
            // The index of GET /api/v1/checks/{id}: the stored rows in id order (FindingRepository.findByCheckIdOrderByIdAsc).
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.severity
                    FROM listing_check c JOIN finding f ON f.check_id = c.id
                    WHERE c.client_id = ? AND c.id = ? AND c.pack_id = ?
                    ORDER BY f.id OFFSET ? LIMIT 1""",
                    (rs, i) -> rs.getString("rule_id") == null ? null
                            : new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version"),
                            rs.getString("rule_id"), FeedbackSource.RULE, rs.getString("severity")),
                    clientId, target.checkId(), Pack.LISTING_RULES.id(), target.position());
        } else if (target.pack().equals(Pack.TRADEMARK_RISK.id())) {
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.risk, f.matched_mark IS NOT NULL AS catalog
                    FROM trademark_check c JOIN trademark_check_finding f ON f.check_id = c.id
                    WHERE c.client_id = ? AND c.id = ? AND c.expires_at > now() AND f.position = ?""",
                    (rs, i) -> new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version").trim(),
                            rs.getString("rule_id"), sourceOfTrademark(rs.getBoolean("catalog")), rs.getString("risk")),
                    clientId, target.checkId(), target.position());
        } else {
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.severity
                    FROM variation_check c JOIN variation_check_finding f ON f.check_id = c.id
                    WHERE c.client_id = ? AND c.id = ? AND c.expires_at > now() AND f.position = ?""",
                    (rs, i) -> new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version").trim(),
                            rs.getString("rule_id"), FeedbackSource.RULE, rs.getString("severity")),
                    clientId, target.checkId(), target.position());
        }
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * The source of a trademark finding: {@code CATALOG} when it matched a mark of the catalog, {@code RULE} for the context rules
     * that match none. The one place that decides it; {@link FeedbackStatsQueries} writes the same rule in SQL.
     */
    static FeedbackSource sourceOfTrademark(boolean matchedMark) {
        return matchedMark ? FeedbackSource.CATALOG : FeedbackSource.RULE;
    }
}
