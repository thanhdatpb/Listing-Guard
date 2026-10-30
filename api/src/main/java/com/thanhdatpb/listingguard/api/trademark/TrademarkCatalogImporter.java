package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogHeader;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.file.Path;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a catalog as a new catalog version (ADR-009, PR A2, A2b). A catalog version is never changed: the same import
 * (the version is a digest of the sources, filters and normalization) finds the version it made before and does
 * nothing; any difference is another version.
 *
 * <p><b>Streaming.</b> The data files are read line by line ({@link CatalogImport#scan}) and the kept records go, in
 * batches of {@link TrademarkImportProperties#batchSize()}, into a temporary staging table. PostgreSQL then groups
 * the staging table by normalized mark and inserts one entry per mark (the merge rule of {@link CatalogImport}, in SQL),
 * so nothing but one batch is held in the memory of the application, however large the file is. The staging table is
 * the choice over an external sort because the grouping, the sort and the spill to disk are what the database already
 * does well, the result goes straight into the table in the same transaction, and no extra files or code are needed.
 *
 * <p><b>One transaction.</b> The version row, its source rows, the staging table and the entries are written in one
 * transaction, so a bad line anywhere in a file (or a failure of the database) leaves nothing behind. The log has
 * counts, never marks.
 */
@Component
public class TrademarkCatalogImporter {

    public enum Outcome { IMPORTED, ALREADY_IMPORTED }

    /**
     * @param read     data lines read
     * @param filtered dropped by the filters
     * @param merged   kept lines folded into another line with the same normalized mark
     * @param loaded   entries stored
     */
    public record Counts(int read, int filtered, int merged, int loaded) {
    }

    public record Result(Outcome outcome, long catalogVersionId, CatalogHeader catalog, Counts counts) {
    }

    private static final Logger log = LoggerFactory.getLogger(TrademarkCatalogImporter.class);

    /** Session-local and dropped at the end of the transaction; nothing else sees it. */
    private static final String CREATE_STAGING = """
            CREATE TEMP TABLE trademark_import_staging (
                normalized   TEXT       NOT NULL,
                display      TEXT       NOT NULL,
                nice_classes SMALLINT[] NOT NULL,
                status_rank  SMALLINT   NOT NULL,
                common_word  BOOLEAN    NOT NULL,
                source_id    TEXT       NOT NULL
            ) ON COMMIT DROP""";

    private static final String INSERT_STAGING = """
            INSERT INTO trademark_import_staging (normalized, display, nice_classes, status_rank, common_word, source_id)
            VALUES (?, ?, ?, ?, ?, ?)""";

    /**
     * The merge rule of {@link CatalogImport} in SQL: the smallest display form (ties to the smaller source id), the
     * classes united, the status declared first in {@link MarkStatus}, a common word if any record says so and the
     * distinct source ids ascending. {@code COLLATE "C"} is code point order in a UTF-8 database, the order of the Java
     * reference.
     */
    private static final String INSERT_ENTRIES = """
            INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, common_word,
                source_ids)
            SELECT ?, g.normalized, g.display, c.classes, %s, g.common_word, s.source_ids
            FROM (SELECT normalized,
                         (array_agg(display ORDER BY display COLLATE "C", source_id COLLATE "C"))[1] AS display,
                         min(status_rank) AS status_rank,
                         bool_or(common_word) AS common_word
                  FROM trademark_import_staging GROUP BY normalized) g
            JOIN (SELECT normalized, array_agg(DISTINCT class ORDER BY class) AS classes
                  FROM trademark_import_staging, unnest(nice_classes) AS class GROUP BY normalized) c
                 ON c.normalized = g.normalized
            JOIN (SELECT normalized, array_agg(source_id ORDER BY source_id COLLATE "C") AS source_ids
                  FROM (SELECT DISTINCT normalized, source_id FROM trademark_import_staging) d GROUP BY normalized) s
                 ON s.normalized = g.normalized
            ORDER BY g.normalized COLLATE "C"
            """.formatted(statusCase());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TrademarkImportProperties properties;

    public TrademarkCatalogImporter(JdbcTemplate jdbc, TransactionTemplate transactions,
                                    TrademarkImportProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** The status of a rank in {@link MarkStatus} declaration order, built from the enum so the two cannot differ. */
    private static String statusCase() {
        StringBuilder text = new StringBuilder("CASE g.status_rank");
        for (MarkStatus status : MarkStatus.values()) {
            text.append(" WHEN ").append(status.ordinal()).append(" THEN '").append(status.name()).append('\'');
        }
        return text.append(" END").toString();
    }

    /**
     * @param rawDir the directory holding the data files named by the manifest entry
     * @throws com.thanhdatpb.listingguard.trademark.catalog.load.CatalogFormatException a file or a line is invalid, a
     *         hash differs or the filters keep nothing: nothing is written
     */
    public Result importCatalog(CatalogManifest.Entry entry, Path rawDir, TextNormalizer normalizer,
                                NormalizationIdentity identity) {
        // a changed or missing file refuses the catalog before the database is touched
        CatalogImport.verifyFiles(entry, rawDir);
        CatalogHeader header = CatalogImport.header(entry, identity);
        Result result;
        try {
            result = transactions.execute(status -> importOrConfirm(entry, rawDir, normalizer, header));
        } catch (DuplicateKeyException e) {
            // another process imported the same version between our read and write; it is the same import
            result = transactions.execute(status -> importOrConfirm(entry, rawDir, normalizer, header));
        }
        Counts counts = result.counts();
        log.info("Trademark catalog {}: {} (read {}, filtered {}, merged {}, loaded {})", header.version(),
                result.outcome(), counts.read(), counts.filtered(), counts.merged(), counts.loaded());
        return result;
    }

    private Result importOrConfirm(CatalogManifest.Entry entry, Path rawDir, TextNormalizer normalizer,
                                   CatalogHeader header) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, label, source_name, source_reference, source_license, source_date, file_sha256,
                       format_version, filters_json, pack_id, normalization_version, normalization_hash,
                       records_read, records_filtered, records_merged, records_loaded
                FROM trademark_catalog_version WHERE version = ?""", header.version());
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (!same(header, row)) {
                throw new IllegalStateException("Catalog version " + header.version() + " exists with other "
                        + "content; versions are never changed. Change the label in the manifest.");
            }
            return new Result(Outcome.ALREADY_IMPORTED, ((Number) row.get("id")).longValue(), header,
                    new Counts(count(row, "records_read"), count(row, "records_filtered"),
                            count(row, "records_merged"), count(row, "records_loaded")));
        }

        jdbc.execute(CREATE_STAGING);
        Stage stage = new Stage();
        CatalogImport.ScanResult scan = CatalogImport.scan(entry, rawDir, normalizer, stage::add);
        stage.flush();
        if (scan.recordsKept() == 0) {
            throw CatalogImport.nothingLeft(header.label());
        }
        int loaded = jdbc.queryForObject("SELECT count(DISTINCT normalized) FROM trademark_import_staging",
                Integer.class);
        Counts counts = new Counts(scan.recordsRead(), scan.recordsRead() - scan.recordsKept(),
                scan.recordsKept() - loaded, loaded);

        long id = jdbc.queryForObject("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license,
                    source_date, file_sha256, format_version, filters_json, pack_id, normalization_version,
                    normalization_hash, records_read, records_filtered, records_merged, records_loaded)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                header.version(), header.label(), header.sourceName(), header.sourceReference(),
                header.sourceLicense(), Date.valueOf(header.sourceDate()), header.fileSha256(),
                header.formatVersion(), header.filtersJson(), header.normalization().packId(),
                header.normalization().normalizationVersion(), header.normalization().normalizationHash(),
                counts.read(), counts.filtered(), counts.merged(), counts.loaded());
        for (CatalogManifest.Source source : header.sources()) {
            jdbc.update("""
                    INSERT INTO trademark_catalog_source (catalog_version_id, source_key, source_name, url, license,
                        source_date, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", id, source.key(), source.sourceName(), source.url(),
                    source.license(), Date.valueOf(source.sourceDate()), source.sha256());
        }
        int inserted = jdbc.update(INSERT_ENTRIES, id);
        if (inserted != loaded) {
            throw new IllegalStateException("Catalog " + header.version() + ": " + inserted + " entries were written "
                    + "but " + loaded + " distinct marks were staged");
        }
        return new Result(Outcome.IMPORTED, id, header, counts);
    }

    /** The records of one batch: sent to the staging table when the batch is full and at the end. */
    private final class Stage {

        private final List<CatalogImport.CatalogRecord> batch = new ArrayList<>(properties.batchSize());

        void add(CatalogImport.CatalogRecord record) {
            batch.add(record);
            if (batch.size() >= properties.batchSize()) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            jdbc.batchUpdate(INSERT_STAGING, batch, batch.size(), (ps, record) -> {
                ps.setString(1, record.normalized());
                ps.setString(2, record.display());
                ps.setArray(3, ps.getConnection().createArrayOf("int2", record.classes().toArray()));
                ps.setInt(4, record.status().ordinal());
                ps.setBoolean(5, record.commonWord());
                ps.setString(6, record.sourceId());
            });
            batch.clear();
        }
    }

    private static int count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private static boolean same(CatalogHeader header, Map<String, Object> row) {
        return header.label().equals(row.get("label"))
                && header.sourceName().equals(row.get("source_name"))
                && header.sourceReference().equals(row.get("source_reference"))
                && header.sourceLicense().equals(row.get("source_license"))
                && header.sourceDate().toString().equals(String.valueOf(row.get("source_date")))
                && header.fileSha256().equals(String.valueOf(row.get("file_sha256")).trim())
                && header.formatVersion().equals(row.get("format_version"))
                && header.filtersJson().equals(row.get("filters_json"))
                && header.normalization().packId().equals(row.get("pack_id"))
                && header.normalization().normalizationVersion().equals(row.get("normalization_version"))
                && header.normalization().normalizationHash().equals(String.valueOf(row.get("normalization_hash")).trim());
    }
}
