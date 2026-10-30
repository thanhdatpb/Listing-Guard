package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=trademark-import --catalog=<version label>} imports one catalog of
 * {@code catalogs/manifest.yaml} from its local file or files in {@code catalogs/raw/} (one or several sources, ADR-009
 * PR A2b), then the application exits (the profile turns the web server off). It reads local files and the database
 * and makes no network call. It prints the
 * catalog version to configure as {@code listing-guard.trademark.catalog-version}. A refused file or an invalid
 * manifest makes the process exit with a non-zero code.
 *
 * <p>A runner and not an HTTP endpoint on purpose, like the policy ingest and the admin job: it is operator work on
 * local files and an endpoint would be attack surface for no benefit.
 */
@Component
@Profile("trademark-import")
public class TrademarkImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrademarkImportRunner.class);

    private final TrademarkProperties properties;
    private final TrademarkRulesets rulesets;
    private final TrademarkCatalogImporter importer;

    public TrademarkImportRunner(TrademarkProperties properties, TrademarkRulesets rulesets,
                                 TrademarkCatalogImporter importer) {
        this.properties = properties;
        this.rulesets = rulesets;
        this.importer = importer;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path dir = Path.of(properties.catalogsDir());
        CatalogManifest manifest = CatalogManifest.load(dir);
        List<String> labels = args.getOptionValues("catalog");
        if (labels == null || labels.size() != 1) {
            throw new IllegalArgumentException("Pass the catalog to import as --catalog=<version>; the manifest has "
                    + manifest.catalogs().stream().map(CatalogManifest.Entry::version).toList());
        }
        log.info("Importing trademark catalog '{}' from {}", labels.get(0), dir.toAbsolutePath());
        PreparedCatalog prepared = CatalogImport.prepare(manifest.entry(labels.get(0)), dir.resolve("raw"),
                new TextNormalizer(rulesets.ruleset().normalization()), NormalizationIdentity.of(rulesets.ruleset()));
        TrademarkCatalogImporter.Result result = importer.importCatalog(prepared);
        log.info("Trademark catalog version {} ({}). To serve it set LISTING_GUARD_TRADEMARK_CATALOG_VERSION "
                + "(listing-guard.trademark.catalog-version) to that version.", prepared.version(), result.outcome());
    }
}
