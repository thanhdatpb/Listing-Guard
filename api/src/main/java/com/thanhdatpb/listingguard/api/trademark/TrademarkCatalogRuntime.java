package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.AnalysisFingerprint;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides at start-up whether trademark checks can be served, and holds what they need (ADR-009).
 *
 * <ul>
 *   <li>{@code DISABLED}: no catalog version is configured (or, following the activation log, none was activated). The
 *       application starts and everything else works.</li>
 *   <li>{@code REFUSED}: the catalog does not exist, or its normalization identity (pack, normalizer
 *       version, hash of the normalization settings) differs from the active ruleset and normalizer. A ruleset that
 *       only changes thresholds or word lists keeps serving it. Marks
 *       normalized one way are never compared with input normalized another way (the same idea as the corpus model
 *       guard of ADR-012). {@code /checks} still works.</li>
 *   <li>{@code READY}: the scanner, with a {@link PostgresTrademarkCatalog} on exactly that version.</li>
 * </ul>
 * Which version: the one named in {@code listing-guard.trademark.catalog-version} (the default), or, with
 * {@code listing-guard.trademark.activation.follow=true}, the one of the latest row of the activation log (ADR-009, PR A2d).
 * Both at once stops the start-up. Following the log, the API reads it again on a schedule ({@link #refreshActivation()})
 * and swaps the whole state in one step; a DEMO catalog is refused there, because the DEMO banner is decided from the
 * configured version only.
 *
 * <p>The PostgreSQL major version and the {@code pg_trgm} extension version are read when a catalog is evaluated, because
 * either can change which candidates the database returns; they are part of the analysis fingerprint (ADR-009).
 */
@Component
public class TrademarkCatalogRuntime implements SmartInitializingSingleton {

    public enum State { DISABLED, READY, REFUSED }

    /** @param detail why, for DISABLED and REFUSED; the catalog version for READY */
    public record Status(State state, String detail) {
    }

    private record Ready(TrademarkScanner scanner, long catalogVersionId, String catalogVersion, String fileSha256,
                         String postgresMajor, String trigramVersion) {
    }

    /**
     * Everything one trademark check needs, read from one consistent state: the scanner, the analysis fingerprint
     * for the marketplace and the catalog version it serves (its row id and its name).
     */
    public record Serving(TrademarkScanner scanner, AnalysisFingerprint fingerprint, long catalogVersionId,
                          String catalogVersion) {
    }

    /**
     * One evaluation, replaced as a whole.
     *
     * @param activationId the activation it came from when following the log; null otherwise or when none exists
     */
    private record Snapshot(Status status, Ready ready, Long activationId) {
    }

    private static final Logger log = LoggerFactory.getLogger(TrademarkCatalogRuntime.class);

    private final TrademarkProperties properties;
    private final TrademarkActivationProperties activation;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TrademarkRulesets rulesets;

    private volatile Snapshot snapshot = new Snapshot(new Status(State.DISABLED, "not evaluated"), null, null);

    /** Serves the configured version; does not follow the activation log. */
    public TrademarkCatalogRuntime(TrademarkProperties properties, JdbcTemplate jdbc, TransactionTemplate transactions,
                                   TrademarkRulesets rulesets) {
        this(properties, TrademarkActivationProperties.off(), jdbc, transactions, rulesets);
    }

    /**
     * @throws IllegalStateException when a catalog version is configured and the activation log is followed too
     */
    @Autowired
    public TrademarkCatalogRuntime(TrademarkProperties properties, TrademarkActivationProperties activation,
                                   JdbcTemplate jdbc, TransactionTemplate transactions, TrademarkRulesets rulesets) {
        if (activation.follow() && properties.catalogConfigured()) {
            throw new IllegalStateException("listing-guard.trademark.catalog-version (TRADEMARK_CATALOG_VERSION) is set and "
                    + "listing-guard.trademark.activation.follow (TRADEMARK_FOLLOW_ACTIVATION) is true: choose one. Empty "
                    + "the catalog version to serve the activation log, or turn following off to serve the configured "
                    + "version.");
        }
        this.properties = properties;
        this.activation = activation;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rulesets = rulesets;
    }

    @Override
    public void afterSingletonsInstantiated() {
        report(evaluate());
    }

    private static void report(Status result) {
        switch (result.state()) {
            case READY -> log.info("Trademark checks are ready on catalog version {}", result.detail());
            case DISABLED -> log.info("Trademark checks are off: {}", result.detail());
            case REFUSED -> log.warn("Trademark checks are refused: {}", result.detail());
        }
    }

    /** Whether the version comes from the activation log. */
    public boolean followsActivation() {
        return activation.follow();
    }

    /** Checks the catalog against the active ruleset and normalizer, and replaces the state with the outcome in one step. */
    public Status evaluate() {
        Snapshot next = activation.follow() ? fromActivation() : fromConfiguration();
        snapshot = next;
        return next.status();
    }

    /**
     * Following the activation log, evaluates again when its latest row changed since the last evaluation; otherwise does
     * nothing. Called on a schedule by the API ({@link TrademarkActivationRefreshSchedule}).
     *
     * @return true when the state was evaluated again
     */
    public boolean refreshActivation() {
        if (!activation.follow()) {
            return false;
        }
        Long latest = jdbc.queryForObject("SELECT max(id) FROM trademark_catalog_activation", Long.class);
        if (Objects.equals(latest, snapshot.activationId())) {
            return false;
        }
        Status result = evaluate();
        log.info("The trademark catalog activation log changed (activation {}).", latest);
        report(result);
        return true;
    }

    private Snapshot fromConfiguration() {
        if (!properties.catalogConfigured()) {
            return new Snapshot(new Status(State.DISABLED, "no catalog version is configured "
                    + "(listing-guard.trademark.catalog-version)"), null, null);
        }
        String version = properties.catalogVersion();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, version, file_sha256, pack_id, normalization_version, normalization_hash
                FROM trademark_catalog_version WHERE version = ?""", version);
        if (rows.isEmpty()) {
            return new Snapshot(new Status(State.REFUSED, "catalog version '" + version + "' does not exist; "
                    + "import it with the trademark-import job and configure the version it prints"), null, null);
        }
        return load(rows.get(0), null, "Import the catalog again with the active ruleset and configure the new version.");
    }

    private Snapshot fromActivation() {
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT id, catalog_version_id, catalog_version FROM trademark_catalog_activation ORDER BY id DESC LIMIT 1""");
        if (latest.isEmpty()) {
            return new Snapshot(new Status(State.DISABLED, "no catalog version has been activated yet "
                    + "(listing-guard.trademark.activation.follow is on; the uspto-sync job or `admin catalog activate` "
                    + "activates one)"), null, null);
        }
        Map<String, Object> row = latest.get(0);
        long activationId = ((Number) row.get("id")).longValue();
        String version = (String) row.get("catalog_version");
        Object versionId = row.get("catalog_version_id");
        if (versionId == null) {
            return new Snapshot(new Status(State.REFUSED, "the activated catalog version '" + version + "' was deleted; "
                    + "activate another one with `admin catalog activate` or `admin catalog rollback`"), null, activationId);
        }
        if (TrademarkProperties.isDemo(version)) {
            return new Snapshot(new Status(State.REFUSED, "the activated catalog version '" + version + "' is a DEMO "
                    + "catalog, which is served only through listing-guard.trademark.catalog-version"), null, activationId);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, version, file_sha256, pack_id, normalization_version, normalization_hash
                FROM trademark_catalog_version WHERE id = ?""", ((Number) versionId).longValue());
        if (rows.isEmpty()) {
            return new Snapshot(new Status(State.REFUSED, "the activated catalog version '" + version + "' does not "
                    + "exist"), null, activationId);
        }
        return load(rows.get(0), activationId, "Import the catalog again with the active ruleset and activate the new "
                + "version.");
    }

    private Snapshot load(Map<String, Object> row, Long activationId, String remedy) {
        String version = (String) row.get("version");
        NormalizationIdentity stored = new NormalizationIdentity((String) row.get("pack_id"),
                (String) row.get("normalization_version"), ((String) row.get("normalization_hash")).trim());
        NormalizationIdentity active = NormalizationIdentity.of(rulesets.ruleset());
        List<String> differences = differences(stored, active);
        if (!differences.isEmpty()) {
            return new Snapshot(new Status(State.REFUSED, "catalog version '" + version + "' was normalized with "
                    + String.join("; ", differences) + ". " + remedy), null, activationId);
        }
        long id = ((Number) row.get("id")).longValue();
        PostgresTrademarkCatalog catalog = new PostgresTrademarkCatalog(jdbc, transactions, id, version);
        Ready ready = new Ready(new TrademarkScanner(rulesets.ruleset(), catalog), id, version,
                ((String) row.get("file_sha256")).trim(), postgresMajor(), trigramVersion());
        return new Snapshot(new Status(State.READY, version), ready, activationId);
    }

    private static List<String> differences(NormalizationIdentity stored, NormalizationIdentity active) {
        List<String> differences = new ArrayList<>();
        compare(differences, "pack", stored.packId(), active.packId());
        compare(differences, "normalizer version", stored.normalizationVersion(), active.normalizationVersion());
        compare(differences, "normalization settings hash", stored.normalizationHash(), active.normalizationHash());
        return differences;
    }

    private static void compare(List<String> differences, String what, String stored, String active) {
        if (!stored.equals(active)) {
            differences.add(what + " " + stored + " (active: " + active + ")");
        }
    }

    public Status status() {
        return snapshot.status();
    }

    /** @throws TrademarkUnavailableException unless the status is READY */
    public TrademarkScanner scanner() {
        Ready current = requireReady(snapshot);
        return current.scanner();
    }

    /**
     * The analysis fingerprint of a scan for the marketplace: the pack's own components plus the catalog version and
     * file hash, the PostgreSQL major version, the {@code pg_trgm} extension version and the marketplace.
     *
     * @throws TrademarkUnavailableException unless the status is READY
     */
    public AnalysisFingerprint fingerprint(String marketplace) {
        return fingerprint(requireReady(snapshot), marketplace);
    }

    /**
     * The scanner, the fingerprint for the marketplace and the catalog version, from one read of the state, so a
     * re-evaluation between two calls cannot pair a scanner with another catalog's fingerprint.
     *
     * @throws TrademarkUnavailableException unless the status is READY
     * @throws IllegalArgumentException      if trademark checks are not configured for the marketplace
     */
    public Serving serving(String marketplace) {
        Ready current = requireReady(snapshot);
        return new Serving(current.scanner(), fingerprint(current, marketplace), current.catalogVersionId(),
                current.catalogVersion());
    }

    private AnalysisFingerprint fingerprint(Ready current, String marketplace) {
        String code = marketplace.trim().toUpperCase(Locale.ROOT);
        if (properties.marketplaces().stream().noneMatch(m -> m.trim().toUpperCase(Locale.ROOT).equals(code))) {
            throw new IllegalArgumentException("Trademark checks are not configured for marketplace " + marketplace);
        }
        return current.scanner().fingerprint(Map.of(
                "catalog_version", current.catalogVersion(),
                "catalog_file_sha256", current.fileSha256(),
                "pg_major", current.postgresMajor(),
                "pg_trgm_extversion", current.trigramVersion(),
                "marketplace", code.toLowerCase(Locale.ROOT)));
    }

    private static Ready requireReady(Snapshot current) {
        if (current.ready() == null) {
            throw new TrademarkUnavailableException("Trademark checks are unavailable: " + current.status().detail());
        }
        return current.ready();
    }

    private String postgresMajor() {
        Integer versionNumber = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
        return String.valueOf(versionNumber / 10000);
    }

    private String trigramVersion() {
        return jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'", String.class);
    }
}
