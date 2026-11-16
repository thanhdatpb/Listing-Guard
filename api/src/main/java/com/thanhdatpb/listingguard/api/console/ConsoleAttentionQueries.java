package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.console.HomeView.Attention;
import com.thanhdatpb.listingguard.api.console.HomeView.AttentionKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The "needs your attention" list of the console home page (ADR-041): the latest results of one client with an ERROR from the listing
 * rules or a HIGH trademark risk, each with the finding that made it so. It starts from the check history events (the same references
 * the history page lists, which carry the SKU a bulk row was given), joins the stored finding and reads nothing else. Every statement
 * starts with the client's id; a trademark result counts only while it is retained. It writes nothing and costs no unit.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleAttentionQueries {

    /** How many results the home page lists. */
    public static final int LIMIT = 5;

    private static final String SQL = """
            SELECT kind, checked_at, marketplace_code, sku, rule_id, message, subject, identity FROM (
              SELECT 'ERROR' AS kind, e.checked_at, e.marketplace_code, e.sku, f.rule_id, f.message, f.value AS subject,
                     c.content_hash::text AS identity
                FROM check_history_event e
                JOIN listing_check c ON e.pack_id = 'listing-rules' AND c.id = e.check_id AND c.client_id = e.client_id
                JOIN LATERAL (SELECT rule_id, message, value FROM finding WHERE check_id = c.id AND severity = 'ERROR'
                               ORDER BY id LIMIT 1) f ON true
               WHERE e.client_id = ?
              UNION ALL
              SELECT 'TRADEMARK', e.checked_at, e.marketplace_code, e.sku, f.rule_id, f.message, f.snippet, c.input_hash::text
                FROM check_history_event e
                JOIN trademark_check c ON e.pack_id = 'trademark-risk' AND c.id = e.check_id AND c.client_id = e.client_id
                                      AND c.expires_at > ?
                JOIN LATERAL (SELECT rule_id, message, snippet FROM trademark_check_finding WHERE check_id = c.id AND risk = 'HIGH'
                               ORDER BY position LIMIT 1) f ON true
               WHERE e.client_id = ?
            ) attention
            ORDER BY checked_at DESC, kind
            LIMIT ?""";

    /** How many events are read to find {@link #LIMIT} different results: a listing checked again is one result. */
    private static final int WINDOW = 60;

    private final JdbcTemplate jdbc;

    public ConsoleAttentionQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Attention> latest(UUID clientId, Instant now) {
        Timestamp clock = Timestamp.from(now);
        record Row(String identity, Attention attention) {
        }
        List<Row> rows = jdbc.query(SQL, (rs, i) -> new Row(rs.getString("identity"),
                new Attention(AttentionKind.valueOf(rs.getString("kind")), rs.getString("sku"), rs.getString("subject"),
                        rs.getString("rule_id"), rs.getString("message"), rs.getString("marketplace_code"),
                        rs.getTimestamp("checked_at").toInstant())), clientId, clock, clientId, WINDOW);
        // newest first: the first of each listing (its SKU, else its content) and rule stays, the checks after it are the same finding
        Set<String> seen = new HashSet<>();
        List<Attention> latest = new ArrayList<>();
        for (Row row : rows) {
            Attention item = row.attention();
            String listing = item.sku() != null && !item.sku().isBlank() ? "sku:" + item.sku() : "content:" + row.identity();
            if (seen.add(listing + "|" + item.ruleId()) && latest.size() < LIMIT) {
                latest.add(item);
            }
        }
        return List.copyOf(latest);
    }
}
