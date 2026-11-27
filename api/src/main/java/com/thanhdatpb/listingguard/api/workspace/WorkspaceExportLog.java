package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.OutputFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The export log (ADR-063 §7, V60 {@code workspace_export_log}): one row per export, with counts and nothing else. No SKU, no title, no cell,
 * no file. Every statement carries {@code client_id}.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceExportLog {

    /** What a row records. {@code templateId} may be null for a template that was replaced. */
    public record Entry(long id, UUID accountId, UUID templateId, Integer templateVersion, TemplateKind templateKind, OutputFormat format,
                        String encoding, int listings, int blocked, int opened, int untrademarked, int warnings, int replacedCells,
                        UUID exportedBy, Instant exportedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkspaceExportLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void add(UUID clientId, Entry entry) {
        jdbc.update("INSERT INTO workspace_export_log (client_id, account_id, template_id, template_version, template_kind, format, encoding, "
                + "listing_count, blocked_count, opened_count, untrademarked_count, warning_count, replaced_cells, exported_by, exported_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", clientId, entry.accountId(), entry.templateId(), entry.templateVersion(),
                entry.templateKind().name(), entry.format().name(), entry.encoding(), entry.listings(), entry.blocked(), entry.opened(),
                entry.untrademarked(), entry.warnings(), entry.replacedCells(), entry.exportedBy(), Timestamp.from(clock.instant()));
    }

    /** The newest rows of an account, newest first. */
    public List<Entry> recent(UUID clientId, UUID accountId, int limit) {
        return jdbc.query("SELECT id, account_id, template_id, template_version, template_kind, format, encoding, listing_count, blocked_count, "
                + "opened_count, untrademarked_count, warning_count, replaced_cells, exported_by, exported_at FROM workspace_export_log "
                + "WHERE client_id = ? AND account_id = ? ORDER BY id DESC LIMIT ?", (rs, n) -> new Entry(rs.getLong("id"),
                rs.getObject("account_id", UUID.class), rs.getObject("template_id", UUID.class), (Integer) rs.getObject("template_version"),
                TemplateKind.valueOf(rs.getString("template_kind")), OutputFormat.valueOf(rs.getString("format")), rs.getString("encoding"),
                rs.getInt("listing_count"), rs.getInt("blocked_count"), rs.getInt("opened_count"), rs.getInt("untrademarked_count"),
                rs.getInt("warning_count"), rs.getInt("replaced_cells"), rs.getObject("exported_by", UUID.class),
                rs.getObject("exported_at", OffsetDateTime.class).toInstant()), clientId, accountId, limit);
    }
}
