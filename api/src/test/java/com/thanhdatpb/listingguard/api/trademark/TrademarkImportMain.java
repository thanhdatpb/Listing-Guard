package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run by {@link TrademarkCatalogImporterHeapTest} in a JVM with a small heap: the import of {@code TrademarkCatalogImporter}
 * without Spring (a plain data source and transaction manager), printing one line with the outcome and the counts. If
 * the import held the file in memory this process would run out of heap.
 *
 * <p>Arguments: JDBC URL, user, password, the catalogs directory (with {@code manifest.yaml} and {@code raw/}) and the
 * label of the catalog in the manifest.
 */
public final class TrademarkImportMain {

    private TrademarkImportMain() {
    }

    public static void main(String[] args) throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);
        TrademarkCatalogImporter importer = new TrademarkCatalogImporter(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new TrademarkImportProperties(5000));
        TrademarkRuleset ruleset = TrademarkRulesetLoader.parse(new ClassPathResource("rulesets/trademark-risk-2026-11.yaml")
                .getContentAsString(StandardCharsets.UTF_8));
        Path dir = Path.of(args[3]);

        TrademarkCatalogImporter.Result result = importer.importCatalog(CatalogManifest.load(dir).entry(args[4]),
                dir.resolve("raw"), new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));

        TrademarkCatalogImporter.Counts counts = result.counts();
        System.out.println("RESULT " + result.outcome() + " " + result.catalog().version() + " " + counts.read() + " "
                + counts.filtered() + " " + counts.merged() + " " + counts.loaded());
    }
}
