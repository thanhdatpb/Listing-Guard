package com.thanhdatpb.listingguard.trademark.catalog.load;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything that identifies a catalog import and does not depend on the records: the stored version, the summary
 * columns of {@code trademark_catalog_version}, the filters, the normalization identity and the sources (one row each
 * in {@code trademark_catalog_source}). It is known before a data file is parsed, so an import that already exists is
 * recognized without reading the records again.
 *
 * @param version         {@code label-} plus a digest of the identity (see {@link CatalogImport}): a changed file,
 *                        filter, source field or normalization is a different version
 * @param sourceName      the source name, or for several sources {@code key: name} joined by {@code " | "} in key order
 * @param sourceReference the same for the urls
 * @param sourceLicense   the same for the license texts
 * @param sourceDate      the date of the source, or for several the oldest of their dates
 * @param fileSha256      the hash of the file, or for several a hash over the lines {@code <key> <hash>} in key order
 * @param filtersJson     canonical JSON of the filters (sorted keys and values)
 * @param sources         the data files, ordered by key; one source with a null key in the one-file form
 */
public record CatalogHeader(String version, String label, String sourceName, String sourceReference,
                            String sourceLicense, LocalDate sourceDate, String fileSha256, String formatVersion,
                            String filtersJson, NormalizationIdentity normalization,
                            List<CatalogManifest.Source> sources) {

    public CatalogHeader {
        sources = List.copyOf(sources);
    }
}
