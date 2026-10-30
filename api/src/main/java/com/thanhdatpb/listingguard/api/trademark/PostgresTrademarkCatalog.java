package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TrademarkCatalog} over the {@code trademark_entry} rows of one catalog version (ADR-009). It only reads, and
 * only that version. The check path makes no network call.
 *
 * <p>{@link #fuzzyCandidates} sets the candidate threshold with {@code set_config('pg_trgm.similarity_threshold', t,
 * true)} inside its own transaction and then uses the {@code %} operator, so the threshold is the ruleset's, never the
 * session's: the local setting ends with the transaction and leaves the connection as it was. Many forms go in one
 * query ({@code unnest}). Results are ordered by form, then by mark in binary order, which is the order of
 * {@code InMemoryTrademarkCatalog}.
 */
public final class PostgresTrademarkCatalog implements TrademarkCatalog {

    private static final String COLUMNS = "e.normalized, e.display, e.nice_classes, e.status, e.common_word, e.source_ids";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final long catalogVersionId;
    private final String version;

    public PostgresTrademarkCatalog(JdbcTemplate jdbc, TransactionTemplate transactions, long catalogVersionId,
                                    String version) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.catalogVersionId = catalogVersionId;
        this.version = version;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Map<String, CatalogMark> findExact(Collection<String> normalizedForms) {
        Map<String, CatalogMark> found = new LinkedHashMap<>();
        if (normalizedForms.isEmpty()) {
            return found;
        }
        jdbc.query("SELECT " + COLUMNS + " FROM trademark_entry e WHERE e.catalog_version_id = ? "
                        + "AND e.normalized = ANY (?) ORDER BY e.normalized COLLATE \"C\"",
                ps -> {
                    ps.setLong(1, catalogVersionId);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", normalizedForms.toArray()));
                },
                rs -> {
                    CatalogMark mark = mark(rs);
                    found.put(mark.normalized(), mark);
                });
        return found;
    }

    @Override
    public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> normalizedForms,
                                                           double candidateThreshold) {
        Map<String, List<CatalogMark>> candidates = new LinkedHashMap<>();
        if (normalizedForms.isEmpty()) {
            return candidates;
        }
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('pg_trgm.similarity_threshold', ?, true)", String.class,
                    BigDecimal.valueOf(candidateThreshold).toPlainString());
            jdbc.query("SELECT f.form, " + COLUMNS + " FROM unnest(?::text[]) AS f(form) "
                            + "JOIN trademark_entry e ON e.catalog_version_id = ? AND e.normalized % f.form "
                            + "ORDER BY f.form COLLATE \"C\", e.normalized COLLATE \"C\"",
                    ps -> {
                        ps.setArray(1, ps.getConnection().createArrayOf("text", normalizedForms.toArray()));
                        ps.setLong(2, catalogVersionId);
                    },
                    rs -> {
                        candidates.computeIfAbsent(rs.getString("form"), form -> new ArrayList<>()).add(mark(rs));
                    });
        });
        return candidates;
    }

    private static CatalogMark mark(ResultSet rs) throws SQLException {
        Array classes = rs.getArray("nice_classes");
        List<Integer> niceClasses = new ArrayList<>();
        for (Object value : (Object[]) classes.getArray()) {
            niceClasses.add(((Number) value).intValue());
        }
        List<String> sourceIds = new ArrayList<>();
        for (Object value : (Object[]) rs.getArray("source_ids").getArray()) {
            sourceIds.add((String) value);
        }
        return new CatalogMark(rs.getString("normalized"), rs.getString("display"), niceClasses,
                MarkStatus.valueOf(rs.getString("status")), rs.getBoolean("common_word"), sourceIds);
    }
}
