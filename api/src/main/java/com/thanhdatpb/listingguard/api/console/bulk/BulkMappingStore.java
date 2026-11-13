package com.thanhdatpb.listingguard.api.console.bulk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The column mapping an account used last, per marketplace (ADR-029, V36). It holds what a seller chose, never what a Sheet contains:
 * the label of each mapped column (its header text), the target that column was given and the separator of each list target. A
 * label is at most {@value #LABEL_MAX_CHARS} characters and a mapping at most {@value #MAX_COLUMNS} columns; anything more is left
 * out when it is saved and ignored when it is read, so a row cannot grow without bound and a damaged one cannot break a page.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class BulkMappingStore {

    static final int MAX_COLUMNS = 100;
    static final int LABEL_MAX_CHARS = 120;

    /** {@code columns} maps a column label to a target key of {@code BulkTargets}; {@code separators} maps a list target to a separator key. */
    public record Stored(Map<String, String> columns, Map<String, String> separators) {

        public Stored {
            columns = Map.copyOf(columns);
            separators = Map.copyOf(separators);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BulkMappingStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<Stored> find(UUID accountId, String marketplace) {
        List<String> rows = jdbc.queryForList(
                "SELECT mapping::text FROM console_bulk_mapping WHERE account_id = ? AND marketplace = ?", String.class,
                accountId, marketplace);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(rows.get(0));
            return Optional.of(new Stored(strings(root.path("columns")), strings(root.path("separators"))));
        } catch (JsonProcessingException | RuntimeException e) {
            // a row that cannot be read is the same as no row: the page falls back to the guess
            return Optional.empty();
        }
    }

    /** Replaces the account's mapping for the marketplace. A mapping with no column is not saved. */
    public void save(UUID accountId, String marketplace, Stored stored) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stored.columns().entrySet()) {
            if (columns.size() < MAX_COLUMNS && entry.getKey().codePointCount(0, entry.getKey().length()) <= LABEL_MAX_CHARS) {
                columns.put(entry.getKey(), entry.getValue());
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("columns", columns, "separators", stored.separators()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The column mapping cannot be written as JSON.", e);
        }
        jdbc.update("INSERT INTO console_bulk_mapping (account_id, marketplace, mapping, updated_at) VALUES (?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (account_id, marketplace) DO UPDATE SET mapping = EXCLUDED.mapping, updated_at = EXCLUDED.updated_at",
                accountId, marketplace, json, Timestamp.from(clock.instant()));
    }

    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && out.size() < MAX_COLUMNS) {
                    out.put(entry.getKey(), entry.getValue().textValue());
                }
            });
        }
        return out;
    }
}
