package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.AnalysisFingerprint;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides at start-up whether trademark checks can be served, and holds what they need (ADR-009).
 *
 * <ul>
 *   <li>{@code DISABLED}: no catalog version is configured. The application starts and everything else works.</li>
 *   <li>{@code REFUSED}: the configured catalog does not exist, or its normalization identity (pack, normalizer
 *       version, hash of the normalization settings) differs from the active ruleset and normalizer. A ruleset that
 *       only changes thresholds or word lists keeps serving it. Marks
 *       normalized one way are never compared with input normalized another way (the same idea as the corpus model
 *       guard of ADR-012). {@code /checks} still works.</li>
 *   <li>{@code READY}: the scanner, with a {@link PostgresTrademarkCatalog} on exactly the configured version.</li>
 * </ul>
 * The PostgreSQL major version and the {@code pg_trgm} extension version are read once at start-up, because either
 * can change which candidates the database returns; they are part of the analysis fingerprint (ADR-009).
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

    private static final Logger log = LoggerFactory.getLogger(TrademarkCatalogRuntime.class);

    private final TrademarkProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TrademarkRulesets rulesets;

    private volatile Status status = new Status(State.DISABLED, "not evaluated");
    private volatile Ready ready;

    public TrademarkCatalogRuntime(TrademarkProperties properties, JdbcTemplate jdbc, TransactionTemplate transactions,
                                   TrademarkRulesets rulesets) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rulesets = rulesets;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Status result = evaluate();
        switch (result.state()) {
            case READY -> log.info("Trademark checks are ready on catalog version {}", result.detail());
            case DISABLED -> log.info("Trademark checks are off: {}", result.detail());
            case REFUSED -> log.warn("Trademark checks are refused: {}", result.detail());
        }
    }

    /** Checks the configured catalog against the active ruleset and normalizer, and stores the outcome. */
    public Status evaluate() {
        ready = null;
        if (!properties.catalogConfigured()) {
            return status = new Status(State.DISABLED, "no catalog version is configured "
                    + "(listing-guard.trademark.catalog-version)");
        }
        String version = properties.catalogVersion();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, file_sha256, pack_id, normalization_version, normalization_hash
                FROM trademark_catalog_version WHERE version = ?""", version);
        if (rows.isEmpty()) {
            return status = new Status(State.REFUSED, "catalog version '" + version + "' does not exist; "
                    + "import it with the trademark-import job and configure the version it prints");
        }
        Map<String, Object> row = rows.get(0);
        NormalizationIdentity stored = new NormalizationIdentity((String) row.get("pack_id"),
                (String) row.get("normalization_version"), ((String) row.get("normalization_hash")).trim());
        NormalizationIdentity active = NormalizationIdentity.of(rulesets.ruleset());
        List<String> differences = differences(stored, active);
        if (!differences.isEmpty()) {
            return status = new Status(State.REFUSED, "catalog version '" + version + "' was normalized with "
                    + String.join("; ", differences) + ". Import the catalog again with the active ruleset "
                    + "and configure the new version.");
        }
        long id = ((Number) row.get("id")).longValue();
        PostgresTrademarkCatalog catalog = new PostgresTrademarkCatalog(jdbc, transactions, id, version);
        ready = new Ready(new TrademarkScanner(rulesets.ruleset(), catalog), id, version,
                ((String) row.get("file_sha256")).trim(), postgresMajor(), trigramVersion());
        return status = new Status(State.READY, version);
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
        return status;
    }

    /** @throws TrademarkUnavailableException unless the status is READY */
    public TrademarkScanner scanner() {
        Ready current = requireReady();
        return current.scanner();
    }

    /**
     * The analysis fingerprint of a scan for the marketplace: the pack's own components plus the catalog version and
     * file hash, the PostgreSQL major version, the {@code pg_trgm} extension version and the marketplace.
     *
     * @throws TrademarkUnavailableException unless the status is READY
     */
    public AnalysisFingerprint fingerprint(String marketplace) {
        return fingerprint(requireReady(), marketplace);
    }

    /**
     * The scanner, the fingerprint for the marketplace and the catalog version, from one read of the state, so a
     * re-evaluation between two calls cannot pair a scanner with another catalog's fingerprint.
     *
     * @throws TrademarkUnavailableException unless the status is READY
     * @throws IllegalArgumentException      if trademark checks are not configured for the marketplace
     */
    public Serving serving(String marketplace) {
        Ready current = requireReady();
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

    private Ready requireReady() {
        Ready current = ready;
        if (current == null) {
            throw new TrademarkUnavailableException("Trademark checks are unavailable: " + status.detail());
        }
        return current;
    }

    private String postgresMajor() {
        Integer versionNumber = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
        return String.valueOf(versionNumber / 10000);
    }

    private String trigramVersion() {
        return jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'", String.class);
    }
}
