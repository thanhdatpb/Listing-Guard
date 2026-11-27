package com.thanhdatpb.listingguard.api.workspace;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A file kept between the steps of an import (ADR-059, V57 {@code workspace_import_stage}). It belongs to the person who uploaded it and
 * to their client, is deleted when the import is written or cancelled, and expires after {@code stage-ttl}. Expired files are removed at
 * the next upload, so no scheduler is needed.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ImportStages {

    public record Stage(UUID id, String fileName, byte[] bytes, String sha256) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WorkspaceProperties properties;

    public ImportStages(JdbcTemplate jdbc, Clock clock, WorkspaceProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    public UUID put(UUID clientId, UUID accountId, String fileName, byte[] bytes) {
        jdbc.update("DELETE FROM workspace_import_stage WHERE expires_at < ?", Timestamp.from(clock.instant()));
        UUID id = UUID.randomUUID();
        String name = fileName == null ? null : fileName.length() > 255 ? fileName.substring(0, 255) : fileName;
        jdbc.update("INSERT INTO workspace_import_stage (id, client_id, created_by, file_name, file_bytes, file_sha256, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, clientId, accountId, name, bytes, WorkspaceJson.sha256(bytes),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plus(properties.stageTtl())));
        return id;
    }

    /** The file of this person's stage, while it has not expired; another client's or another person's id is simply absent. */
    public Optional<Stage> get(UUID clientId, UUID accountId, UUID id) {
        return jdbc.query("SELECT id, file_name, file_bytes, file_sha256 FROM workspace_import_stage WHERE id = ? AND client_id = ? "
                + "AND created_by = ? AND expires_at >= ?", (rs, n) -> new Stage(rs.getObject("id", UUID.class), rs.getString("file_name"),
                rs.getBytes("file_bytes"), rs.getString("file_sha256")), id, clientId, accountId, Timestamp.from(clock.instant()))
                .stream().findFirst();
    }

    public void delete(UUID clientId, UUID id) {
        jdbc.update("DELETE FROM workspace_import_stage WHERE id = ? AND client_id = ?", id, clientId);
    }
}
