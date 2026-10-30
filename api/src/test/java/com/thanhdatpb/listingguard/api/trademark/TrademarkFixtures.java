package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic catalog files for the trademark tests. */
final class TrademarkFixtures {

    static final String SYNTHETIC = "catalogs/synthetic-marks.jsonl";

    private TrademarkFixtures() {
    }

    static String resource(String path) {
        try (InputStream in = TrademarkFixtures.class.getResourceAsStream("/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes {@code manifest.yaml} and {@code raw/synthetic.jsonl} into {@code dir}, with the right hash, for one
     * catalog called {@code label}.
     */
    static Path catalogsDir(Path dir, String label, String data, String classes, String statuses) {
        try {
            Files.createDirectories(dir.resolve("raw"));
            Files.writeString(dir.resolve("raw").resolve("synthetic.jsonl"), data);
            Files.writeString(dir.resolve("manifest.yaml"), """
                    catalogs:
                      - version: %s
                        file: synthetic.jsonl
                        format_version: jsonl-1
                        source_name: Synthetic marks
                        url: https://example.invalid/synthetic
                        license: test only
                        source_date: 2026-10-30
                        sha256: %s
                        filters:
                          include_classes: %s
                          include_status: %s
                    """.formatted(label, CatalogImport.sha256(data.getBytes(StandardCharsets.UTF_8)), classes, statuses));
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path syntheticDir(Path dir, String label) {
        return catalogsDir(dir, label, resource(SYNTHETIC), "[25, 35]", "[REGISTERED, PENDING]");
    }

    /** The in-memory reference of the import (the merge rule applied in Java). */
    static PreparedCatalog prepare(Path dir, String label, TrademarkRuleset ruleset) {
        return CatalogImport.prepare(CatalogManifest.load(dir).entry(label), dir.resolve("raw"),
                new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
    }

    /** The real import: streamed into the staging table and merged by SQL. */
    static TrademarkCatalogImporter.Result importCatalog(TrademarkCatalogImporter importer, Path dir, String label,
                                                         TrademarkRuleset ruleset) {
        return importer.importCatalog(CatalogManifest.load(dir).entry(label), dir.resolve("raw"),
                new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
    }
}
