package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Synthetic catalogs for the tests of the workspace's background audit (ADR-061): a few invented marks written as a jsonl-1 file, imported
 * through the real importer and, when asked, activated in the log. Nothing here is a real mark or a real file.
 */
public final class AuditCatalogFixtures {

    private AuditCatalogFixtures() {
    }

    /** One line of a jsonl-1 file: a registered mark in Nice class 25. */
    public static String registered(String mark, String sourceId) {
        return "{\"mark\":\"" + mark + "\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"" + sourceId + "\"}";
    }

    /**
     * Imports a catalog called {@code label} made of {@code lines} and returns the version name it was stored under.
     * The ruleset is the one the application serves, so the import has the normalization identity it needs.
     */
    public static String importCatalog(TrademarkCatalogImporter importer, TrademarkRulesets rulesets, String label, String... lines) {
        try {
            Path dir = Files.createTempDirectory("audit-catalog-" + label);
            Path root = TrademarkFixtures.catalogsDir(dir, label, String.join("\n", lines) + "\n", "[25, 35]", "[REGISTERED, PENDING]");
            PreparedCatalog prepared = TrademarkFixtures.prepare(root, label, rulesets.ruleset());
            TrademarkFixtures.importCatalog(importer, root, label, rulesets.ruleset());
            return prepared.version();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
