package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The activation log of trademark catalog versions (ADR-009, PR A2d, V31): which stored version is served by a runtime that
 * follows the log ({@code listing-guard.trademark.activation.follow=true}). A row is only ever added: activating, rolling
 * back and activating again are new rows, and the latest row names the served version.
 *
 * <p>A version can be activated only when it exists, is not a DEMO catalog (a DEMO catalog is served only through
 * {@code listing-guard.trademark.catalog-version}, which is what decides the DEMO banner), and was normalized like the active
 * ruleset normalizes (the runtime would refuse it otherwise). Callers that change the log while a sync could run hold
 * {@link TrademarkCatalogLock}.
 */
@Component
public class CatalogActivations {

    /** Why a version was activated; keep in sync with the {@code CHECK} of V31. */
    public enum Reason { SYNC, ROLLBACK, MANUAL }

    /**
     * One row of the log.
     *
     * @param catalogVersionId null once that version was deleted; {@code catalogVersion} still names it
     */
    public record Activation(long id, Long catalogVersionId, String catalogVersion, Reason reason, Long syncRunId,
                             String note, Instant activatedAt) {
    }

    /** A stored catalog version. */
    public record CatalogVersion(long id, String version, String label, LocalDate sourceDate, Instant importedAt,
                                 int entries, NormalizationIdentity normalization) {
    }

    static final int MAX_NOTE_CHARS = 500;

    private static final Logger log = LoggerFactory.getLogger(CatalogActivations.class);

    private static final String VERSION_COLUMNS = """
            id, version, label, source_date, imported_at, records_loaded, pack_id, normalization_version,
            normalization_hash""";

    private static final RowMapper<Activation> ACTIVATION = (rs, i) -> new Activation(rs.getLong("id"),
            nullableLong(rs, "catalog_version_id"), rs.getString("catalog_version"), Reason.valueOf(rs.getString("reason")),
            nullableLong(rs, "sync_run_id"), rs.getString("note"), rs.getTimestamp("activated_at").toInstant());

    private static final RowMapper<CatalogVersion> VERSION = (rs, i) -> new CatalogVersion(rs.getLong("id"),
            rs.getString("version"), rs.getString("label"), rs.getDate("source_date").toLocalDate(),
            rs.getTimestamp("imported_at").toInstant(), rs.getInt("records_loaded"),
            new NormalizationIdentity(rs.getString("pack_id"), rs.getString("normalization_version"),
                    rs.getString("normalization_hash").trim()));

    private final JdbcTemplate jdbc;
    private final TrademarkRulesets rulesets;

    public CatalogActivations(JdbcTemplate jdbc, TrademarkRulesets rulesets) {
        this.jdbc = jdbc;
        this.rulesets = rulesets;
    }

    /** The latest activation: what a runtime that follows the log serves. */
    public Optional<Activation> current() {
        return jdbc.query("SELECT * FROM trademark_catalog_activation ORDER BY id DESC LIMIT 1", ACTIVATION).stream()
                .findFirst();
    }

    /** The id of the latest activation, without reading the row; empty when nothing was ever activated. */
    public Optional<Long> currentId() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(id) FROM trademark_catalog_activation", Long.class));
    }

    /** The latest activations, newest first. */
    public List<Activation> history(int limit) {
        return jdbc.query("SELECT * FROM trademark_catalog_activation ORDER BY id DESC LIMIT ?", ACTIVATION, limit);
    }

    /** Every stored catalog version, newest first. */
    public List<CatalogVersion> catalogs() {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM trademark_catalog_version ORDER BY id DESC", VERSION);
    }

    public Optional<CatalogVersion> find(String version) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM trademark_catalog_version WHERE version = ?", VERSION,
                version).stream().findFirst();
    }

    public Optional<CatalogVersion> find(long id) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM trademark_catalog_version WHERE id = ?", VERSION, id)
                .stream().findFirst();
    }

    /**
     * Why the version cannot be served through the log, or null when it can.
     */
    public String problem(CatalogVersion version) {
        if (TrademarkProperties.isDemo(version.version())) {
            return "'" + version.version() + "' is a DEMO catalog (invented marks); a DEMO catalog is served only through "
                    + "listing-guard.trademark.catalog-version, never through the activation log";
        }
        NormalizationIdentity active = NormalizationIdentity.of(rulesets.ruleset());
        if (!active.equals(version.normalization())) {
            return "'" + version.version() + "' was normalized with " + version.normalization()
                    + ", the active ruleset normalizes with " + active + "; import it again with the active ruleset";
        }
        return null;
    }

    /**
     * Activates a stored version by name.
     *
     * @throws IllegalArgumentException when it does not exist or cannot be served
     */
    public Activation activate(String version, Reason reason, Long syncRunId, String note) {
        CatalogVersion target = find(version).orElseThrow(() -> new IllegalArgumentException("Catalog version '" + version
                + "' does not exist; `admin catalog list` shows the stored versions."));
        return activate(target, reason, syncRunId, note);
    }

    /**
     * Activates a stored version by id.
     *
     * @throws IllegalArgumentException when it does not exist or cannot be served
     */
    public Activation activate(long catalogVersionId, Reason reason, Long syncRunId, String note) {
        CatalogVersion target = find(catalogVersionId).orElseThrow(() -> new IllegalArgumentException(
                "Catalog version id " + catalogVersionId + " does not exist."));
        return activate(target, reason, syncRunId, note);
    }

    private Activation activate(CatalogVersion target, Reason reason, Long syncRunId, String note) {
        String problem = problem(target);
        if (problem != null) {
            throw new IllegalArgumentException("Cannot activate " + problem + ".");
        }
        String bounded = note == null || note.isBlank() ? null : printable(note);
        Map<String, Object> row = jdbc.queryForMap("""
                INSERT INTO trademark_catalog_activation (catalog_version_id, catalog_version, reason, sync_run_id, note)
                VALUES (?, ?, ?, ?, ?) RETURNING id, activated_at""", target.id(), target.version(), reason.name(),
                syncRunId, bounded);
        Activation activation = new Activation(((Number) row.get("id")).longValue(), target.id(), target.version(), reason,
                syncRunId, bounded, ((Timestamp) row.get("activated_at")).toInstant());
        log.info("Trademark catalog version {} activated ({}{}), activation {}", target.version(), reason,
                syncRunId == null ? "" : ", uspto-sync run " + syncRunId, activation.id());
        return activation;
    }

    /**
     * Activates the newest version older than the served one that was served before and can still be served, or the named
     * one, as a {@link Reason#ROLLBACK}.
     *
     * @param toVersion the version to serve; null for the one before
     * @throws IllegalStateException    when nothing was ever activated, or there is no earlier version to serve
     * @throws IllegalArgumentException when the named version does not exist, cannot be served or is already served
     */
    public Activation rollback(String toVersion, String note) {
        Activation current = current().orElseThrow(() -> new IllegalStateException(
                "No catalog version has been activated, so there is nothing to roll back."));
        if (toVersion != null) {
            CatalogVersion target = find(toVersion).orElseThrow(() -> new IllegalArgumentException("Catalog version '"
                    + toVersion + "' does not exist; `admin catalog list` shows the stored versions."));
            if (current.catalogVersionId() != null && current.catalogVersionId() == target.id()) {
                throw new IllegalArgumentException("'" + toVersion + "' is already the served version.");
            }
            return activate(target, Reason.ROLLBACK, null, note);
        }
        long below = current.catalogVersionId() == null ? Long.MAX_VALUE : current.catalogVersionId();
        List<String> skipped = new ArrayList<>();
        for (CatalogVersion candidate : jdbc.query("SELECT " + VERSION_COLUMNS + """
                 FROM trademark_catalog_version v
                WHERE v.id < ? AND EXISTS (SELECT 1 FROM trademark_catalog_activation a WHERE a.catalog_version_id = v.id)
                ORDER BY v.id DESC""", VERSION, below)) {
            String problem = problem(candidate);
            if (problem == null) {
                return activate(candidate, Reason.ROLLBACK, null, note);
            }
            skipped.add(problem);
        }
        throw new IllegalStateException("No earlier catalog version that was served can be served again"
                + (skipped.isEmpty() ? "" : " (" + String.join("; ", skipped) + ")")
                + ". Name one with --to=<version>.");
    }

    private static String printable(String text) {
        StringBuilder out = new StringBuilder();
        text.strip().codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return out.length() <= MAX_NOTE_CHARS ? out.toString() : out.substring(0, MAX_NOTE_CHARS);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
