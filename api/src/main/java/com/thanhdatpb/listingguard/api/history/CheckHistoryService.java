package com.thanhdatpb.listingguard.api.history;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only history assembled from persisted check results and lightweight actor references. */
@Service
public class CheckHistoryService {
    public static final int PAGE_SIZE = 25;
    private static final String BASE = """
            WITH rows AS (
              SELECT e.id AS history_id, e.client_id, e.pack_id, e.check_id, e.checked_at, e.marketplace_code,
                     CASE WHEN e.actor_kind = 'ACCOUNT' THEN COALESCE(a.email, e.actor_id)
                          ELSE COALESCE(k.label, e.actor_id) END AS checker,
                     e.actor_id, e.row_count, e.sku, c.status::text AS status,
                     (SELECT count(*) FROM finding f WHERE f.check_id = c.id) AS finding_count,
                     (SELECT count(*) FROM finding f WHERE f.check_id = c.id AND f.severity = 'ERROR') AS error_count,
                     0::bigint AS trademark_count,
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = e.client_id
                       AND v.pack_id = e.pack_id AND v.check_id = e.check_id) AS reviewed
                FROM check_history_event e JOIN listing_check c ON e.pack_id = 'listing-rules' AND c.id = e.check_id AND c.client_id = e.client_id
                LEFT JOIN account a ON e.actor_kind = 'ACCOUNT' AND a.id::text = e.actor_id AND a.client_id = e.client_id
                LEFT JOIN api_key k ON e.actor_kind = 'API_KEY' AND k.key_id = e.actor_id AND k.client_id = e.client_id
               WHERE e.client_id = :client
              UNION ALL
              SELECT c.id, c.client_id, 'listing-rules', c.id, c.created_at, c.marketplace_code, '—', '', 1, NULL,
                     c.status::text,
                     (SELECT count(*) FROM finding f WHERE f.check_id = c.id),
                     (SELECT count(*) FROM finding f WHERE f.check_id = c.id AND f.severity = 'ERROR'), 0::bigint,
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = c.client_id
                       AND v.pack_id = 'listing-rules' AND v.check_id = c.id)
                FROM listing_check c WHERE c.client_id = :client AND NOT EXISTS
                  (SELECT 1 FROM check_history_event e WHERE e.client_id = c.client_id AND e.pack_id = 'listing-rules' AND e.check_id = c.id)
              UNION ALL
              SELECT e.id, e.client_id, e.pack_id, e.check_id, e.checked_at, e.marketplace_code,
                     CASE WHEN e.actor_kind = 'ACCOUNT' THEN COALESCE(a.email, e.actor_id)
                          ELSE COALESCE(k.label, e.actor_id) END,
                     e.actor_id, e.row_count, e.sku, c.completeness::text, 0::bigint, 0::bigint,
                     (SELECT count(*) FROM trademark_check_finding f WHERE f.check_id = c.id),
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = e.client_id
                       AND v.pack_id = e.pack_id AND v.check_id = e.check_id)
                FROM check_history_event e JOIN trademark_check c ON e.pack_id = 'trademark-risk' AND c.id = e.check_id AND c.client_id = e.client_id
                LEFT JOIN account a ON e.actor_kind = 'ACCOUNT' AND a.id::text = e.actor_id AND a.client_id = e.client_id
                LEFT JOIN api_key k ON e.actor_kind = 'API_KEY' AND k.key_id = e.actor_id AND k.client_id = e.client_id
               WHERE e.client_id = :client AND c.expires_at > now()
              UNION ALL
              SELECT c.id, c.client_id, 'trademark-risk', c.id, c.created_at, c.marketplace_code, '—', '', 1, NULL,
                     c.completeness::text, 0::bigint, 0::bigint,
                     (SELECT count(*) FROM trademark_check_finding f WHERE f.check_id = c.id),
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = c.client_id
                       AND v.pack_id = 'trademark-risk' AND v.check_id = c.id)
                FROM trademark_check c WHERE c.client_id = :client AND c.expires_at > now() AND NOT EXISTS
                  (SELECT 1 FROM check_history_event e WHERE e.client_id = c.client_id AND e.pack_id = 'trademark-risk' AND e.check_id = c.id)
              UNION ALL
              SELECT e.id, e.client_id, e.pack_id, e.check_id, e.checked_at, e.marketplace_code,
                     CASE WHEN e.actor_kind = 'ACCOUNT' THEN COALESCE(a.email, e.actor_id)
                          ELSE COALESCE(k.label, e.actor_id) END,
                     e.actor_id, e.row_count, e.sku, c.completeness::text,
                     (SELECT count(*) FROM variation_check_finding f WHERE f.check_id = c.id AND f.severity = 'ERROR'),
                     0::bigint, 0::bigint,
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = e.client_id
                       AND v.pack_id = e.pack_id AND v.check_id = e.check_id)
                FROM check_history_event e JOIN variation_check c ON e.pack_id = 'variation-checks' AND c.id = e.check_id AND c.client_id = e.client_id
                LEFT JOIN account a ON e.actor_kind = 'ACCOUNT' AND a.id::text = e.actor_id AND a.client_id = e.client_id
                LEFT JOIN api_key k ON e.actor_kind = 'API_KEY' AND k.key_id = e.actor_id AND k.client_id = e.client_id
               WHERE e.client_id = :client AND c.expires_at > now()
              UNION ALL
              SELECT c.id, c.client_id, 'variation-checks', c.id, c.created_at, c.marketplace_code, '—', '', 1, NULL,
                     c.completeness::text,
                     (SELECT count(*) FROM variation_check_finding f WHERE f.check_id = c.id AND f.severity = 'ERROR'),
                     0::bigint, 0::bigint,
                     EXISTS (SELECT 1 FROM finding_verdict v WHERE v.client_id = c.client_id
                       AND v.pack_id = 'variation-checks' AND v.check_id = c.id)
                FROM variation_check c WHERE c.client_id = :client AND c.expires_at > now() AND NOT EXISTS
                  (SELECT 1 FROM check_history_event e WHERE e.client_id = c.client_id AND e.pack_id = 'variation-checks' AND e.check_id = c.id)
            )
            SELECT history_id, pack_id, check_id, checked_at, marketplace_code, checker, actor_id, row_count, sku, status,
                   finding_count, error_count, trademark_count, reviewed FROM rows
            WHERE (CAST(:startDate AS timestamptz) IS NULL OR checked_at >= CAST(:startDate AS timestamptz))
              AND (CAST(:endDate AS timestamptz) IS NULL OR checked_at < CAST(:endDate AS timestamptz))
              AND (:status = '' OR lower(status) = lower(:status))
              AND (:marketplace = '' OR marketplace_code = :marketplace)
              AND (:checker = '' OR lower(checker) LIKE lower(:checkerLike) OR actor_id = :checker)
            """;

    public record Query(LocalDate from, LocalDate to, String status, String marketplace, String checker, int page) { }
    public record Row(UUID historyId, String pack, UUID checkId, java.time.Instant checkedAt, String marketplace, String checker,
                      int rowCount, String sku, String status, long findings, long errors, long trademarks, boolean reviewed) { }
    public record Page(List<Row> rows, long total, int page, int pages, Query query) { }

    private final NamedParameterJdbcTemplate jdbc;

    public CheckHistoryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page page(UUID clientId, Query query) {
        int page = Math.max(0, query.page());
        var params = new MapSqlParameterSource()
                .addValue("client", clientId)
                .addValue("startDate", query.from() == null ? null : query.from().atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .addValue("endDate", query.to() == null ? null : query.to().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .addValue("status", clean(query.status()))
                .addValue("marketplace", clean(query.marketplace()))
                .addValue("checker", clean(query.checker()))
                .addValue("checkerLike", "%" + clean(query.checker()) + "%");
        long total = jdbc.queryForObject("SELECT count(*) FROM (" + BASE + ") history", params, Long.class);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        List<Row> rows = jdbc.query(BASE + " ORDER BY checked_at DESC, history_id DESC LIMIT " + PAGE_SIZE + " OFFSET "
                        + ((long) page * PAGE_SIZE), params,
                (rs, i) -> new Row(rs.getObject("history_id", UUID.class), rs.getString("pack_id"),
                        rs.getObject("check_id", UUID.class), rs.getTimestamp("checked_at").toInstant(),
                        rs.getString("marketplace_code"), rs.getString("checker"), rs.getInt("row_count"),
                        rs.getString("sku"), rs.getString("status"), rs.getLong("finding_count"),
                        rs.getLong("error_count"), rs.getLong("trademark_count"), rs.getBoolean("reviewed")));
        return new Page(rows, total, page, pages, query);
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }
}
