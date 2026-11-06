package com.thanhdatpb.listingguard.api.personalization;

import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Makes the trademark scan READY in a test context with the DEMO catalog of this repository ({@code catalogs/demo/}: invented
 * words such as {@code Zibblefrax}, never a real mark). The catalog is imported once the schema exists and
 * {@code listing-guard.trademark.catalog-version} names it; its version is a function of the manifest entry, the file and the
 * normalization identity, so it is known before the import. The runtime evaluates at start-up, before the import, so it is
 * told to evaluate again afterwards. Tests run with the {@code api} module as working directory.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PersonalizationDemoCatalogTestConfig {

    /** The label of the entry in {@code catalogs/demo/manifest.yaml}. */
    static final String LABEL = "demo-fictional-2026-11";

    private static final Path DIR = Path.of("..", "catalogs", "demo").toAbsolutePath().normalize();
    private static final TrademarkRuleset RULESET = ruleset();
    private static final PreparedCatalog PREPARED = CatalogImport.prepare(CatalogManifest.load(DIR).entry(LABEL),
            DIR.resolve("raw"), new TextNormalizer(RULESET.normalization()), NormalizationIdentity.of(RULESET));

    /** The catalog version these contexts serve. */
    static String catalogVersion() {
        return PREPARED.version();
    }

    private static TrademarkRuleset ruleset() {
        try {
            return TrademarkRulesetLoader.parse(
                    new ClassPathResource("rulesets/trademark-risk-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar demoCatalogVersion() {
        return registry -> registry.add("listing-guard.trademark.catalog-version", PREPARED::version);
    }

    @Bean
    ApplicationRunner demoCatalogImport(TrademarkCatalogImporter importer, TrademarkCatalogRuntime runtime) {
        return args -> {
            importer.importCatalog(CatalogManifest.load(DIR).entry(LABEL), DIR.resolve("raw"),
                    new TextNormalizer(RULESET.normalization()), NormalizationIdentity.of(RULESET));
            runtime.evaluate();
        };
    }
}
