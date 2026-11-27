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
    public record Resolved(String marketplace, String analysisVersion, String ruleId, FeedbackSource source, String level,
                           String rulesetVersion, String catalogVersion, String productType) {
    }

    private final JdbcTemplate jdbc;

    public FeedbackFindings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Resolved> resolve(UUID clientId, FeedbackTarget target) {
        if (!FeedbackTarget.PACKS.contains(target.pack())) {
            return Optional.empty();
        }
        List<Resolved> rows;
        if (target.pack().equals(Pack.LISTING_RULES.id())) {
            // The index of GET /api/v1/checks/{id}: the stored rows in id order (FindingRepository.findByCheckIdOrderByIdAsc).
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.severity, r.version AS ruleset_version
                    FROM listing_check c JOIN finding f ON f.check_id = c.id
                    JOIN ruleset_version r ON r.id = c.ruleset_version_id
                    WHERE c.client_id = ? AND c.id = ? AND c.pack_id = ?
                    ORDER BY f.id OFFSET ? LIMIT 1""",
                    (rs, i) -> rs.getString("rule_id") == null ? null
                            : new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version"),
                            rs.getString("rule_id"), FeedbackSource.RULE, rs.getString("severity"),
                            rs.getString("ruleset_version"), null, null),
                    clientId, target.checkId(), Pack.LISTING_RULES.id(), target.position());
        } else if (target.pack().equals(Pack.TRADEMARK_RISK.id())) {
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.risk, f.matched_mark IS NOT NULL AS catalog,
                           r.version AS ruleset_version, v.version AS catalog_version, c.product_type
                    FROM trademark_check c JOIN trademark_check_finding f ON f.check_id = c.id
                    JOIN ruleset_version r ON r.id = c.ruleset_version_id
                    LEFT JOIN trademark_catalog_version v ON v.id = c.catalog_version_id
                    WHERE c.client_id = ? AND c.id = ? AND c.expires_at > now() AND f.position = ?""",
                    (rs, i) -> new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version").trim(),
                            rs.getString("rule_id"), sourceOfTrademark(rs.getBoolean("catalog")), rs.getString("risk"),
                            rs.getString("ruleset_version"), rs.getString("catalog_version"), rs.getString("product_type")),
                    clientId, target.checkId(), target.position());
        } else {
            rows = jdbc.query("""
                    SELECT c.marketplace_code, c.analysis_version, f.rule_id, f.severity, r.version AS ruleset_version
                    FROM variation_check c JOIN variation_check_finding f ON f.check_id = c.id
                    JOIN ruleset_version r ON r.id = c.ruleset_version_id
                    WHERE c.client_id = ? AND c.id = ? AND c.expires_at > now() AND f.position = ?""",
                    (rs, i) -> new Resolved(rs.getString("marketplace_code"), rs.getString("analysis_version").trim(),
                            rs.getString("rule_id"), FeedbackSource.RULE, rs.getString("severity"),
                            rs.getString("ruleset_version"), null, null),
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
