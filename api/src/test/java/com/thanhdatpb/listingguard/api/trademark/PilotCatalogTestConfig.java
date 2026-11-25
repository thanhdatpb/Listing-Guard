package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** A versioned synthetic jsonl-2 catalog for the pilot deployment tests. */
@TestConfiguration(proxyBeanMethods = false)
public class PilotCatalogTestConfig {
    private static final String LABEL = "pilot-synthetic";
    private static final TrademarkRuleset RULESET = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml");
    private static final Path DIRECTORY = catalog();
    private static final PreparedCatalog PREPARED = TrademarkFixtures.prepare(DIRECTORY, LABEL, RULESET);

    private static Path catalog() {
        try {
            Path root = TrademarkFixtures.catalogsDir(Files.createTempDirectory("trademark-pilot-catalog"), LABEL, """
                    {"mark":"CERAMIC","status":"REGISTERED","nice_classes":[25,35],"source_id":"PILOT-001","word_count":1,
                    "same_mark_count":7,"same_mark_class_count":2,"mark_kind":"STANDARD_CHARACTER","dictionary_word":true,
                    "distinctiveness_tier":"WEAK","distinctiveness_reason":"COMMON_OR_REPEATED"}
                    """.replace("\n", "") + "\n", "[25, 35]", "[REGISTERED]");
            Path manifest = root.resolve("manifest.yaml");
            Files.writeString(manifest, Files.readString(manifest).replace("jsonl-1", "jsonl-2"));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar pilotCatalogVersion() {
        return registry -> registry.add("listing-guard.trademark.catalog-version", PREPARED::version);
    }

    @Bean
    ApplicationRunner pilotCatalogImport(TrademarkCatalogImporter importer, TrademarkCatalogRuntime runtime) {
        return args -> {
            TrademarkFixtures.importCatalog(importer, DIRECTORY, LABEL, RULESET);
            runtime.evaluate();
        };
    }
}
