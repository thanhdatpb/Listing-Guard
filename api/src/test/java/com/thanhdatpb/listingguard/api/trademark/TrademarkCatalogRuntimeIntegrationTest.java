package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.trademark.AnalysisFingerprint;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.catalog.load.PreparedCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class TrademarkCatalogRuntimeIntegrationTest {

    @Autowired
    TrademarkCatalogRuntime defaultRuntime;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @TempDir
    Path dir;

    private TrademarkCatalogRuntime runtimeFor(String catalogVersion) {
        return new TrademarkCatalogRuntime(new TrademarkProperties("classpath:rulesets/trademark-risk-2026-11.yaml",
                List.of("ETSY", "AMAZON"), catalogVersion, "catalogs"), jdbc, transactions, rulesets);
    }

    private PreparedCatalog importSynthetic(NormalizationIdentity identity, TextNormalizer normalizer) {
        String label = "runtime-" + UUID.randomUUID().toString().substring(0, 8);
        TrademarkFixtures.syntheticDir(dir, label);
        PreparedCatalog prepared = CatalogImport.prepare(CatalogManifest.load(dir).entry(label), dir.resolve("raw"),
                normalizer, identity);
        importer.importCatalog(prepared);
        return prepared;
    }

    private PreparedCatalog importWithActiveRuleset() {
        TrademarkRuleset active = rulesets.ruleset();
        return importSynthetic(NormalizationIdentity.of(active), new TextNormalizer(active.normalization()));
    }

    @Test
    void withNoCatalogConfiguredTrademarkChecksAreOffAndTheApplicationStarted() {
        assertThat(defaultRuntime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.DISABLED);
        assertThat(defaultRuntime.status().detail()).contains("catalog-version");
        assertThatThrownBy(defaultRuntime::scanner).isInstanceOf(TrademarkUnavailableException.class);
        assertThatThrownBy(() -> defaultRuntime.fingerprint("ETSY")).isInstanceOf(TrademarkUnavailableException.class);
    }

    @Test
    void aCatalogImportedWithTheActiveRulesetIsServed() {
        PreparedCatalog prepared = importWithActiveRuleset();
        TrademarkCatalogRuntime runtime = runtimeFor(prepared.version());

        TrademarkCatalogRuntime.Status status = runtime.evaluate();

        assertThat(status).isEqualTo(new TrademarkCatalogRuntime.Status(TrademarkCatalogRuntime.State.READY,
                prepared.version()));
        TrademarkResult result = runtime.scanner().scan(TrademarkCheckInput.ofTitle("Gift for Zorvex fans"));
        assertThat(result.catalogVersion()).isEqualTo(prepared.version());
        assertThat(result.findings()).extracting(f -> f.evidence().matchedMark()).containsExactly("ZORVEX");
    }

    @Test
    void theConfiguredVersionIsExactNotTheLatest() {
        PreparedCatalog older = importWithActiveRuleset();
        importWithActiveRuleset();

        TrademarkCatalogRuntime runtime = runtimeFor(older.version());
        runtime.evaluate();

        assertThat(runtime.status().detail()).isEqualTo(older.version());
        assertThat(runtime.scanner().scan(TrademarkCheckInput.ofTitle("x")).catalogVersion()).isEqualTo(older.version());
    }

    @Test
    void anUnknownVersionIsRefused() {
        TrademarkCatalogRuntime runtime = runtimeFor("no-such-version-0123456789ab");

        TrademarkCatalogRuntime.Status status = runtime.evaluate();

        assertThat(status.state()).isEqualTo(TrademarkCatalogRuntime.State.REFUSED);
        assertThat(status.detail()).contains("does not exist").contains("trademark-import");
        assertThatThrownBy(runtime::scanner).isInstanceOf(TrademarkUnavailableException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void aCatalogNormalizedWithAnotherRulesetIsRefused() throws Exception {
        String yaml = new ClassPathResource("rulesets/trademark-risk-2026-11.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        TrademarkRuleset edited = TrademarkRulesetLoader.parse(yaml + "\n# an edit that changes the content hash\n");
        PreparedCatalog prepared = importSynthetic(NormalizationIdentity.of(edited),
                new TextNormalizer(edited.normalization()));
        TrademarkCatalogRuntime runtime = runtimeFor(prepared.version());

        TrademarkCatalogRuntime.Status status = runtime.evaluate();

        assertThat(status.state()).isEqualTo(TrademarkCatalogRuntime.State.REFUSED);
        assertThat(status.detail()).contains("ruleset content hash " + edited.contentHash())
                .contains("(active: " + rulesets.ruleset().contentHash() + ")").contains("Import the catalog again");
        assertThat(status.detail()).doesNotContain("pack ").doesNotContain("normalizer version");
        assertThatThrownBy(runtime::scanner).isInstanceOf(TrademarkUnavailableException.class);
    }

    @Test
    void aCatalogNormalizedWithAnotherNormalizerVersionOrRulesetVersionIsRefused() {
        TrademarkRuleset active = rulesets.ruleset();
        PreparedCatalog otherNormalizer = importSynthetic(new NormalizationIdentity(active.packId(), active.version(),
                active.contentHash(), "normalization-0"), new TextNormalizer(active.normalization()));
        PreparedCatalog otherVersion = importSynthetic(new NormalizationIdentity(active.packId(), "trademark-risk-2020-01",
                active.contentHash(), TextNormalizer.VERSION), new TextNormalizer(active.normalization()));

        assertThat(runtimeFor(otherNormalizer.version()).evaluate().detail()).contains("normalizer version normalization-0");
        assertThat(runtimeFor(otherVersion.version()).evaluate().detail()).contains("ruleset version trademark-risk-2020-01");
    }

    @Test
    void theFingerprintCarriesTheCatalogDatabaseAndMarketplaceComponents() {
        PreparedCatalog prepared = importWithActiveRuleset();
        TrademarkCatalogRuntime runtime = runtimeFor(prepared.version());
        runtime.evaluate();

        AnalysisFingerprint etsy = runtime.fingerprint("ETSY");

        String major = jdbc.queryForObject("SELECT (current_setting('server_version_num')::int / 10000)::text", String.class);
        String extversion = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'", String.class);
        assertThat(major).isEqualTo("16");
        assertThat(etsy.descriptor()).contains("pack_id=trademark-risk\n")
                .contains("ruleset_hash=" + rulesets.ruleset().contentHash() + "\n")
                .contains("external.catalog_version=" + prepared.version() + "\n")
                .contains("external.catalog_file_sha256=" + prepared.fileSha256() + "\n")
                .contains("external.pg_major=" + major + "\n")
                .contains("external.pg_trgm_extversion=" + extversion + "\n")
                .contains("external.marketplace=etsy\n");
        assertThat(runtime.fingerprint("amazon").value()).isNotEqualTo(etsy.value());
        assertThat(runtime.fingerprint(" etsy ")).isEqualTo(etsy);
        assertThatThrownBy(() -> runtime.fingerprint("WALMART")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anotherCatalogVersionIsAnotherFingerprint() {
        TrademarkCatalogRuntime first = runtimeFor(importWithActiveRuleset().version());
        TrademarkCatalogRuntime second = runtimeFor(importWithActiveRuleset().version());
        first.evaluate();
        second.evaluate();

        // same file and filters but a different label: a different version, so a different fingerprint
        assertThat(first.fingerprint("ETSY").value()).isNotEqualTo(second.fingerprint("ETSY").value());
    }
}
