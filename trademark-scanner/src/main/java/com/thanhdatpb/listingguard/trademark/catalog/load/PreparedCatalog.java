package com.thanhdatpb.listingguard.trademark.catalog.load;

import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import java.time.LocalDate;
import java.util.List;

/**
 * A validated, filtered, normalized and merged catalog, ready to be written. Nothing here depends on a database.
 *
 * @param version         {@code label-} plus a digest of the identity below, so a changed file, filter, source
 *                        field or normalization is a different version and an unchanged import finds the same one
 * @param filtersJson     canonical JSON of the filters (sorted keys and values)
 * @param recordsRead     data lines read
 * @param recordsFiltered lines dropped by the filters
 * @param recordsMerged   kept lines folded into another line with the same normalized mark
 * @param entries         the marks to store (each with the ids of the records merged into it), ordered by normalized
 *                        form
 */
public record PreparedCatalog(String version, String label, String sourceName, String sourceReference,
                              String sourceLicense, LocalDate sourceDate, String fileSha256, String formatVersion,
                              String filtersJson, NormalizationIdentity normalization, int recordsRead,
                              int recordsFiltered, int recordsMerged, List<Entry> entries) {

    /** A mark to store. Its source ids are in {@link CatalogMark#sourceIds()}. */
    public record Entry(CatalogMark mark) {
    }

    public PreparedCatalog {
        entries = List.copyOf(entries);
    }

    public int recordsLoaded() {
        return entries.size();
    }
}
