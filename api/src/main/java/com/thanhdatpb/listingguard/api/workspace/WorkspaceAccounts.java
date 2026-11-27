package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** The seller accounts of the workspace (ADR-059, V57). Every statement carries {@code client_id}. */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceAccounts {

    static final int NAME_MAX = 120;

    private static final RowMapper<SellerAccount> MAPPER = (rs, n) -> new SellerAccount(rs.getObject("id", UUID.class),
            rs.getObject("client_id", UUID.class), Marketplace.valueOf(rs.getString("marketplace")), rs.getString("name"),
            rs.getString("brand"), ExportFormat.valueOf(rs.getString("default_export_format")), rs.getBoolean("enabled"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkspaceAccounts(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public SellerAccount create(UUID clientId, Marketplace marketplace, String name, String brand, ExportFormat format) {
        String cleanName = cleanName(name);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO workspace_account (id, client_id, marketplace, name, brand, default_export_format, enabled, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)", id, clientId, marketplace.name(), cleanName, blankToNull(brand),
                    format.name(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new WorkspaceException("account.duplicate");
        }
        return find(clientId, id).orElseThrow();
    }

    /** Changes the name, brand, export format and switch of an account. The marketplace of an account never changes. */
    public boolean update(UUID clientId, UUID id, String name, String brand, ExportFormat format, boolean enabled) {
        try {
            return jdbc.update("UPDATE workspace_account SET name = ?, brand = ?, default_export_format = ?, enabled = ?, updated_at = ? "
                    + "WHERE id = ? AND client_id = ?", cleanName(name), blankToNull(brand), format.name(), enabled,
                    Timestamp.from(clock.instant()), id, clientId) == 1;
        } catch (DuplicateKeyException e) {
            throw new WorkspaceException("account.duplicate");
        }
    }

    public Optional<SellerAccount> find(UUID clientId, UUID id) {
        return jdbc.query("SELECT * FROM workspace_account WHERE id = ? AND client_id = ?", MAPPER, id, clientId).stream().findFirst();
    }

    public List<SellerAccount> list(UUID clientId) {
        return jdbc.query("SELECT * FROM workspace_account WHERE client_id = ? ORDER BY marketplace, lower(name)", MAPPER, clientId);
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new WorkspaceException("account.name.required");
        }
        if (clean.codePointCount(0, clean.length()) > NAME_MAX) {
            throw new WorkspaceException("account.name.long");
        }
        return clean;
    }

    private static String blankToNull(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.codePointCount(0, clean.length()) > NAME_MAX) {
            throw new WorkspaceException("account.name.long");
        }
        return clean.isEmpty() ? null : clean;
    }
}
