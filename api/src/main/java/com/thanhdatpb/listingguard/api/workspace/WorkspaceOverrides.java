package com.thanhdatpb.listingguard.api.workspace;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * The reasons an ADMIN gave for letting a blocked listing go to "ready to export" (ADR-062 §7, V59 {@code workspace_status_override}). A
 * reason is about one content of one listing: it counts only while the listing's content hash is the one stored with it. Every statement
 * carries {@code client_id}.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceOverrides {

    public static final int REASON_MAX = 500;

    /** One opened block. {@code blockReasons} are the codes that blocked the listing, joined by commas. */
    public record Override(long id, UUID listingId, UUID openedBy, String reason, Instant openedAt, String contentHash, String auditState,
                           String blockReasons) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkspaceOverrides(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void add(UUID clientId, UUID listingId, UUID openedBy, String reason, String contentHash, String auditState, String blockReasons) {
        jdbc.update("INSERT INTO workspace_status_override (client_id, listing_id, opened_by, reason, opened_at, content_hash, audit_state, "
                + "block_reasons) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clientId, listingId, openedBy, reason, Timestamp.from(clock.instant()),
                contentHash, auditState, blockReasons);
    }

    /** The newest opened block of each of these listings. */
    public Map<UUID, Override> latest(UUID clientId, Collection<UUID> listingIds) {
        Map<UUID, Override> found = new HashMap<>();
        List<UUID> all = new ArrayList<>(listingIds);
        for (int from = 0; from < all.size(); from += 1000) {
            UUID[] chunk = all.subList(from, Math.min(all.size(), from + 1000)).toArray(UUID[]::new);
            jdbc.query("SELECT DISTINCT ON (listing_id) id, listing_id, opened_by, reason, opened_at, content_hash, audit_state, block_reasons "
                    + "FROM workspace_status_override WHERE client_id = ? AND listing_id = ANY (?) ORDER BY listing_id, id DESC",
                    (RowCallbackHandler) rs -> found.put(rs.getObject("listing_id", UUID.class), new Override(rs.getLong("id"),
                            rs.getObject("listing_id", UUID.class), rs.getObject("opened_by", UUID.class), rs.getString("reason"),
                            rs.getObject("opened_at", OffsetDateTime.class).toInstant(), rs.getString("content_hash"), rs.getString("audit_state"),
                            rs.getString("block_reasons"))), clientId, chunk);
        }
        return found;
    }
}
