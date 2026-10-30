package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.sql.Array;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a {@link PreparedCatalog} as a new catalog version (ADR-009, PR A2). A catalog version is never changed: the
 * same import (the version is a digest of the file, filters, source and normalization) finds the version it made
 * before and does nothing; any difference is another version. The version row and all its entries are written in
 * one transaction, so a failure leaves nothing behind. The log has counts, never marks.
 */
@Component
public class TrademarkCatalogImporter {

    public enum Outcome { IMPORTED, ALREADY_IMPORTED }

    public record Result(Outcome outcome, long catalogVersionId, PreparedCatalog catalog) {
    }

    private static final Logger log = LoggerFactory.getLogger(TrademarkCatalogImporter.class);
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TrademarkCatalogImporter(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Result importCatalog(PreparedCatalog catalog) {
        Result result;
        try {
            result = transactions.execute(status -> importOrConfirm(catalog));
        } catch (DuplicateKeyException e) {
            // another process imported the same version between our read and write; it is the same import
            result = transactions.execute(status -> importOrConfirm(catalog));
        }
        log.info("Trademark catalog {}: {} (read {}, filtered {}, merged {}, loaded {})", catalog.version(),
                result.outcome(), catalog.recordsRead(), catalog.recordsFiltered(), catalog.recordsMerged(),
                catalog.recordsLoaded());
        return result;
    }

    private Result importOrConfirm(PreparedCatalog catalog) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, label, source_name, source_reference, source_license, source_date, file_sha256,
                       format_version, filters_json, pack_id, normalization_version, normalization_hash
                FROM trademark_catalog_version WHERE version = ?""", catalog.version());
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (!same(catalog, row)) {
                throw new IllegalStateException("Catalog version " + catalog.version() + " exists with other "
                        + "content; versions are never changed. Change the label in the manifest.");
            }
            return new Result(Outcome.ALREADY_IMPORTED, ((Number) row.get("id")).longValue(), catalog);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license,
                    source_date, file_sha256, format_version, filters_json, pack_id, normalization_version,
                    normalization_hash, records_read, records_filtered, records_merged, records_loaded)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                catalog.version(), catalog.label(), catalog.sourceName(), catalog.sourceReference(),
                catalog.sourceLicense(), Date.valueOf(catalog.sourceDate()), catalog.fileSha256(),
                catalog.formatVersion(), catalog.filtersJson(), catalog.normalization().packId(),
                catalog.normalization().normalizationVersion(), catalog.normalization().normalizationHash(),
                catalog.recordsRead(), catalog.recordsFiltered(), catalog.recordsMerged(), catalog.recordsLoaded());
        jdbc.batchUpdate("""
                INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status,
                    common_word, source_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", catalog.entries(), BATCH, (ps, entry) -> {
            Array classes = ps.getConnection().createArrayOf("int2", entry.mark().niceClasses().toArray());
            ps.setLong(1, id);
            ps.setString(2, entry.mark().normalized());
            ps.setString(3, entry.mark().display());
            ps.setArray(4, classes);
            ps.setString(5, entry.mark().status().name());
            ps.setBoolean(6, entry.mark().commonWord());
            ps.setArray(7, ps.getConnection().createArrayOf("text", entry.mark().sourceIds().toArray()));
        });
        return new Result(Outcome.IMPORTED, id, catalog);
    }

    private static boolean same(PreparedCatalog catalog, Map<String, Object> row) {
        return catalog.label().equals(row.get("label"))
                && catalog.sourceName().equals(row.get("source_name"))
                && catalog.sourceReference().equals(row.get("source_reference"))
                && catalog.sourceLicense().equals(row.get("source_license"))
                && catalog.sourceDate().toString().equals(String.valueOf(row.get("source_date")))
                && catalog.fileSha256().equals(String.valueOf(row.get("file_sha256")).trim())
                && catalog.formatVersion().equals(row.get("format_version"))
                && catalog.filtersJson().equals(row.get("filters_json"))
                && catalog.normalization().packId().equals(row.get("pack_id"))
                && catalog.normalization().normalizationVersion().equals(row.get("normalization_version"))
                && catalog.normalization().normalizationHash().equals(String.valueOf(row.get("normalization_hash")).trim());
    }
}
