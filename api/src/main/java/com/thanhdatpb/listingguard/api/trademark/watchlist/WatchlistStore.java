package com.thanhdatpb.listingguard.api.trademark.watchlist;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and writes client watchlists (ADR-031, V37). Every statement is scoped by {@code client_id}. {@link #lock} and
 * {@link #replace} must run in the caller's transaction.
 */
@Component
public class WatchlistStore {

    /** The list's header: its revision and its last change. */
    public record Header(long revision, Instant updatedAt, String updatedBy) {
    }

    /** One stored entry. */
    public record StoredEntry(UUID id, String mark, List<String> aliases, List<Integer> niceClasses, String serialNumber,
                              String note, boolean enabled, String addedBy, Instant addedAt, String updatedBy,
                              Instant updatedAt) {

        public StoredEntry {
            aliases = List.copyOf(aliases);
            niceClasses = List.copyOf(niceClasses);
        }
    }

    private final JdbcTemplate jdbc;

    public WatchlistStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Header> header(UUID clientId) {
        return jdbc.query("SELECT revision, updated_at, updated_by FROM trademark_watchlist WHERE client_id = ?",
                rs -> rs.next() ? Optional.of(new Header(rs.getLong("revision"), instant(rs, "updated_at"),
                        rs.getString("updated_by"))) : Optional.<Header>empty(), clientId);
    }

    /** Every entry of the client's list, in its order. */
    public List<StoredEntry> entries(UUID clientId) {
        return jdbc.query("""
                SELECT id, mark, aliases, nice_classes, serial_number, note, enabled, added_by, added_at, updated_by, updated_at
                FROM trademark_watchlist_entry WHERE client_id = ? ORDER BY position""", (rs, i) -> entry(rs), clientId);
    }

    /** The enabled entries only, for a scan. */
    public List<StoredEntry> enabledEntries(UUID clientId) {
        return jdbc.query("""
                SELECT id, mark, aliases, nice_classes, serial_number, note, enabled, added_by, added_at, updated_by, updated_at
                FROM trademark_watchlist_entry WHERE client_id = ? AND enabled ORDER BY position""", (rs, i) -> entry(rs),
                clientId);
    }

    /**
     * Holds the client's row lock until the transaction ends, so two changes of one list are applied one after the other.
     * The same lock {@code ClientProvisioning} takes for keys: a short write, never held across a request.
     *
     * @return the current revision, 0 when the client has never had a list
     */
    public long lock(UUID clientId) {
        jdbc.query("SELECT id FROM client WHERE id = ? FOR UPDATE", rs -> null, clientId);
        return header(clientId).map(Header::revision).orElse(0L);
    }

    /** Replaces every entry and sets the header to {@code revision}. */
    public void replace(UUID clientId, long revision, String actor, Instant now, List<StoredEntry> entries) {
        jdbc.update("""
                INSERT INTO trademark_watchlist (client_id, revision, updated_at, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (client_id) DO UPDATE SET revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by""", clientId, revision, Timestamp.from(now), actor);
        jdbc.update("DELETE FROM trademark_watchlist_entry WHERE client_id = ?", clientId);
        if (entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO trademark_watchlist_entry (id, client_id, position, mark, aliases, nice_classes, serial_number, note,
                    enabled, added_by, added_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                StoredEntry entry = entries.get(i);
                ps.setObject(1, entry.id());
                ps.setObject(2, clientId);
                ps.setInt(3, i);
                ps.setString(4, entry.mark());
                ps.setArray(5, ps.getConnection().createArrayOf("text", entry.aliases().toArray()));
                ps.setArray(6, ps.getConnection().createArrayOf("integer", entry.niceClasses().toArray()));
                ps.setString(7, entry.serialNumber());
                ps.setString(8, entry.note());
                ps.setBoolean(9, entry.enabled());
                ps.setString(10, entry.addedBy());
                ps.setTimestamp(11, Timestamp.from(entry.addedAt()));
                ps.setString(12, entry.updatedBy());
                ps.setTimestamp(13, Timestamp.from(entry.updatedAt()));
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });
    }

    private static StoredEntry entry(ResultSet rs) throws SQLException {
        return new StoredEntry(rs.getObject("id", UUID.class), rs.getString("mark"), strings(rs.getArray("aliases")),
                integers(rs.getArray("nice_classes")), rs.getString("serial_number"), rs.getString("note"),
                rs.getBoolean("enabled"), rs.getString("added_by"), instant(rs, "added_at"), rs.getString("updated_by"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return values;
    }

    private static List<Integer> integers(Array array) throws SQLException {
        List<Integer> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add(((Number) value).intValue());
        }
        return values;
    }
}
