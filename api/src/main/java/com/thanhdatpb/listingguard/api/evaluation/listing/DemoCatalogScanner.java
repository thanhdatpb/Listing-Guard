package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.file.Path;

/**
 * The trademark scanner of the labelled sample set (ADR-032): the given ruleset with the DEMO catalog of
 * {@code catalogs/demo/} (invented marks, ADR-009), held in memory. The sample set is scanned with it and never with the
 * catalog a deployment serves, so the expected trademark findings of a case do not change with the catalog, and no case needs
 * a real mark.
 */
public final class DemoCatalogScanner {

    private DemoCatalogScanner() {
    }

    /**
     * @param demoDir the DEMO catalog directory (its {@code manifest.yaml} and {@code raw/})
     * @throws IllegalStateException when the directory does not hold exactly one catalog whose label starts with {@code demo}
     */
    public static TrademarkScanner load(TrademarkRuleset ruleset, Path demoDir) {
        CatalogManifest manifest = CatalogManifest.load(demoDir);
        if (manifest.catalogs().size() != 1 || !manifest.catalogs().get(0).version().startsWith("demo")) {
            throw new IllegalStateException(demoDir + " is not the DEMO catalog: the labelled sample set is scanned only with "
                    + "the one fictional catalog of catalogs/demo/.");
        }
        CatalogManifest.Entry entry = manifest.catalogs().get(0);
        PreparedCatalog prepared = CatalogImport.prepare(entry, demoDir.resolve("raw"),
                new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
        return new TrademarkScanner(ruleset, new InMemoryTrademarkCatalog(prepared.version(),
                prepared.entries().stream().map(PreparedCatalog.Entry::mark).toList()));
    }
}
