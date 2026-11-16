package com.thanhdatpb.listingguard.api.console.reaudit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Asks a stored catalog version which marks it holds (ADR-035). A catalog version is immutable and its entries are unique by their
 * match form, so the question is an index lookup on {@code (catalog_version_id, normalized)}: it reads the marks of the findings of
 * one run, never the catalog.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
class PreviousCatalogMarks implements ReauditBaselines.CatalogMarks {

    private final JdbcTemplate jdbc;

    PreviousCatalogMarks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Set<String>> present(String catalogVersion, Collection<String> normalized) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM trademark_catalog_version WHERE version = ?", Long.class, catalogVersion);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.isEmpty()) {
            return Optional.of(Set.of());
        }
        List<String> found = jdbc.query("""
                SELECT e.normalized FROM trademark_entry e WHERE e.catalog_version_id = ? AND e.normalized = ANY (?)""",
                ps -> {
                    ps.setLong(1, ids.get(0));
                    ps.setArray(2, ps.getConnection().createArrayOf("text", normalized.toArray()));
                }, (rs, i) -> rs.getString("normalized"));
        return Optional.of(new LinkedHashSet<>(found));
    }
}
