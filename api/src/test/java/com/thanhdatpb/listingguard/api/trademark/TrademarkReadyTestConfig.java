package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Makes the trademark checks READY in a test context: the synthetic catalog is imported once the schema exists and
 * {@code listing-guard.trademark.catalog-version} names it. The version is known before the import, because it is a
 * function of the manifest entry, the file and the normalization identity ({@code CatalogImport.prepare} needs no
 * database). The runtime evaluates at start-up, before the import, so it is told to evaluate again afterwards. Tests
 * that import this together with {@code TestcontainersConfig} share one cached Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TrademarkReadyTestConfig {

    static final String LABEL = "endpoint-synthetic";

    private static final PreparedCatalog PREPARED = prepare();

    /** The catalog version these contexts serve. */
    public static String catalogVersion() {
        return PREPARED.version();
    }

    private static PreparedCatalog prepare() {
        try {
            Path dir = Files.createTempDirectory("trademark-endpoint-catalog");
            TrademarkFixtures.syntheticDir(dir, LABEL);
            TrademarkRuleset ruleset = TrademarkRulesetLoader.parse(
                    new ClassPathResource("rulesets/trademark-risk-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8));
            return TrademarkFixtures.prepare(dir, LABEL, ruleset);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar trademarkCatalogVersion() {
        return registry -> registry.add("listing-guard.trademark.catalog-version", PREPARED::version);
    }

    @Bean
    ApplicationRunner trademarkCatalogImport(TrademarkCatalogImporter importer, TrademarkCatalogRuntime runtime) {
        return args -> {
            importer.importCatalog(PREPARED);
            runtime.evaluate();
        };
    }
}
