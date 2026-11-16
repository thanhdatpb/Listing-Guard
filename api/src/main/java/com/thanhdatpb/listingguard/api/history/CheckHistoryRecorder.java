package com.thanhdatpb.listingguard.api.history;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/** Records references to checked results for history; never receives a listing or finding text. */
@Component
public class CheckHistoryRecorder {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CheckHistoryRecorder(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void api(AuthenticatedClient actor, String pack, UUID checkId, String marketplace, int rows, String sku) {
        record(actor.id(), pack, checkId, marketplace, "API_KEY", actor.keyId(), rows, sku);
    }

    public void account(UUID clientId, UUID accountId, String pack, UUID checkId, String marketplace, int rows, String sku) {
        record(clientId, pack, checkId, marketplace, "ACCOUNT", accountId.toString(), rows, sku);
    }

    private void record(UUID clientId, String pack, UUID checkId, String marketplace, String actorKind, String actorId,
                        int rows, String sku) {
        if (checkId == null) return;
        String boundedSku = sku == null ? null : sku.codePointCount(0, sku.length()) > 200
                ? sku.substring(0, sku.offsetByCodePoints(0, 200)) : sku;
        jdbc.update("""
                INSERT INTO check_history_event (id, client_id, pack_id, check_id, marketplace_code, actor_kind, actor_id,
                    checked_at, row_count, sku)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), clientId, pack, checkId, marketplace, actorKind, actorId,
                Timestamp.from(clock.instant()), Math.max(1, rows), boundedSku);
    }
}
