package com.thanhdatpb.listingguard.api.policymonitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.rag.analysis.AnalyzerProfile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyImpactReports {
    private static final String SELECT = """
            SELECT r.id, r.marketplace_code, r.status AS source_status, r.effective_date, r.generated_at, r.evidence,
                   COALESCE(d.decision, r.status) AS status, d.decided_at
            FROM policy_impact_report r LEFT JOIN policy_impact_decision d
                ON d.report_id = r.id AND d.client_id = ?
            """;

    public record Report(UUID id, String marketplace, PolicyImpact.Status sourceStatus, PolicyImpact.Status status,
                         LocalDate effectiveDate, OffsetDateTime generatedAt, OffsetDateTime decidedAt,
                         PolicyImpact.Evidence evidence) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PolicyImpactReports(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void publish(PolicyDiffEngine.RunFile run, Map<String, PolicyImpact.Approval> approvals) {
        if (!run.dryRun()) throw new IllegalArgumentException("Impact publication requires a dry-run comparison");
        for (var change : run.changes()) {
            if (!List.of("ETSY", "AMAZON").contains(change.marketplace())) continue;
            var approval = approvals.get(change.url());
            boolean alert = run.alerts().stream().anyMatch(item -> item.url().equals(change.url()));
            var reason = PolicyImpact.reason(change, approval, alert);
            var status = reason == PolicyImpact.Reason.NONE ? PolicyImpact.Status.PROPOSED : PolicyImpact.Status.NEEDS_REVIEW;
            LocalDate effective = status == PolicyImpact.Status.PROPOSED ? approval.effectiveDate() : null;
            var evidence = new PolicyImpact.Evidence(change.title(), change.url(), change.oldVersion(), change.newVersion(),
                    change.oldHash(), change.newHash(), reason, PolicyImpact.sections(change.sections()),
                    status == PolicyImpact.Status.PROPOSED ? run.rulesToReview().stream()
                            .filter(rule -> rule.sourceUrl().equals(change.url()) && rule.marketplace().equals(change.marketplace())).toList()
                            : List.of());
            String json = json(evidence);
            String key = AnalyzerProfile.sha256((change.marketplace() + "\n" + effective + "\n" + json).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO policy_impact_report (id, fingerprint, marketplace_code, status, effective_date, generated_at, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (fingerprint) DO NOTHING
                    """, UUID.randomUUID(), key, change.marketplace(), status.name(), effective,
                    run.generatedAt().atOffset(ZoneOffset.UTC), json);
        }
    }

    public List<Report> list(UUID client, String marketplace, PolicyImpact.Status status, LocalDate from, LocalDate through) {
        return jdbc.query(SELECT + """
                WHERE (CAST(? AS varchar) IS NULL OR r.marketplace_code = ?)
                  AND (CAST(? AS varchar) IS NULL OR COALESCE(d.decision, r.status) = ?)
                  AND (CAST(? AS date) IS NULL OR r.effective_date >= ?)
                  AND (CAST(? AS date) IS NULL OR r.effective_date <= ?)
                ORDER BY r.generated_at DESC, r.id LIMIT 50
                """, this::read, client, marketplace, marketplace, status == null ? null : status.name(),
                status == null ? null : status.name(), from, from, through, through);
    }

    public Optional<Report> get(UUID client, UUID report) {
        return jdbc.query(SELECT + " WHERE r.id = ?", this::read, client, report).stream().findFirst();
    }

    /** The actor and tenant come from the authenticated session; re-check the live role in the write statement. */
    public boolean decide(ConsoleUser user, UUID report, PolicyImpact.Decision decision) {
        return jdbc.update("""
                INSERT INTO policy_impact_decision (client_id, report_id, actor_id, decision)
                SELECT a.client_id, r.id, a.id, ? FROM account a
                JOIN client c ON c.id = a.client_id
                CROSS JOIN policy_impact_report r
                WHERE a.email = ? AND a.client_id = ? AND a.role = 'ADMIN' AND c.active AND r.id = ?
                  AND (r.status = 'PROPOSED' OR ? = 'DEFERRED')
                ON CONFLICT (client_id, report_id) DO NOTHING
                """, decision.name(), user.email(), user.clientId(), report, decision.name()) == 1;
    }

    private Report read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            return new Report(rs.getObject("id", UUID.class), rs.getString("marketplace_code"),
                    PolicyImpact.Status.valueOf(rs.getString("source_status")), PolicyImpact.Status.valueOf(rs.getString("status")),
                    rs.getObject("effective_date", LocalDate.class), rs.getObject("generated_at", OffsetDateTime.class),
                    rs.getObject("decided_at", OffsetDateTime.class),
                    mapper.readValue(rs.getString("evidence"), PolicyImpact.Evidence.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode policy impact metadata");
        }
    }

    private String json(PolicyImpact.Evidence evidence) {
        try {
            return mapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode policy impact metadata");
        }
    }
}
