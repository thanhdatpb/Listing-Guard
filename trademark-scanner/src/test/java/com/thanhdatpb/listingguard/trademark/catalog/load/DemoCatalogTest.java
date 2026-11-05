package com.thanhdatpb.listingguard.trademark.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The DEMO catalog in {@code catalogs/demo/} (fictional marks, for local tests and demonstrations). It must stay valid for
 * the shipped trademark ruleset, stay clearly labelled, stay unused by default, and do what {@code catalogs/demo/README.md}
 * says it does. It holds invented words only; this test cannot know whether an invented word is also somebody's mark, so
 * it checks the labels and the source ids, and the README says to take care.
 */
class DemoCatalogTest {

    private static final String LABEL = "demo-fictional-2026-11";
    private static final Path DIR = Path.of("..", "catalogs", "demo");

    private final TrademarkRuleset ruleset = TestRulesets.shipped();
    private final PreparedCatalog prepared = CatalogImport.prepare(CatalogManifest.load(DIR).entry(LABEL), DIR.resolve("raw"),
            new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
    private final TrademarkScanner scanner = new TrademarkScanner(ruleset, new InMemoryTrademarkCatalog(prepared.version(),
            prepared.entries().stream().map(PreparedCatalog.Entry::mark).toList()));

    private TrademarkResult scan(String title) {
        return scanner.scan(TrademarkCheckInput.ofTitle(title));
    }

    private static List<String> ruleIds(TrademarkResult result) {
        return result.findings().stream().map(TrademarkFinding::ruleId).toList();
    }

    @Test
    void theDemoManifestIsValidAndLabelledDemoEverywhere() throws IOException {
        CatalogManifest manifest = CatalogManifest.load(DIR);

        assertThat(manifest.catalogs()).hasSize(1);
        CatalogManifest.Entry entry = manifest.catalogs().get(0);
        assertThat(entry.version()).isEqualTo(LABEL).startsWith("demo");
        CatalogManifest.Source source = entry.sources().get(0);
        assertThat(source.file()).startsWith("DEMO-");
        assertThat(source.sourceName()).contains("DEMO").contains("fictional");
        assertThat(source.license()).contains("DEMO");
        // the stored version is the label plus a digest, and says demo too
        assertThat(prepared.version()).startsWith(LABEL + "-");
        // every record is marked as demo data by its source id
        for (String line : Files.readAllLines(DIR.resolve("raw").resolve(source.file()), StandardCharsets.UTF_8)) {
            assertThat(line).contains("\"source_id\":\"DEMO-");
        }
    }

    @Test
    void theDataFileKeepsLfLineEndingsBecauseItsHashIsChecked() throws IOException {
        byte[] bytes = Files.readAllBytes(DIR.resolve("raw").resolve("DEMO-fictional-marks.jsonl"));

        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\r");
    }

    @Test
    void nothingLoadsTheDemoCatalogByDefault() throws IOException {
        // the main manifest lists no catalog, so the demo cannot be reached through it
        assertThat(CatalogManifest.load(Path.of("..", "catalogs")).catalogs()).isEmpty();
        // the default catalogs directory and the default catalog version carry no demo
        String application = Files.readString(Path.of("..", "api", "src", "main", "resources", "application.yml"),
                StandardCharsets.UTF_8);
        assertThat(application).doesNotContain("catalogs/demo").doesNotContain("demo-fictional");
        assertThat(application).contains("catalogs-dir: ${CATALOGS_DIR:catalogs}");
        String compose = Files.readString(Path.of("..", "docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose).doesNotContain("demo-fictional");
        assertThat(compose).contains("LISTING_GUARD_TRADEMARK_CATALOG_VERSION: ${TRADEMARK_CATALOG_VERSION:-}");
    }

    @Test
    void theFiltersDropTheCancelledMarkAndTheMarkOutsideTheClasses() {
        assertThat(prepared.recordsRead()).isEqualTo(10);
        assertThat(prepared.recordsFiltered()).isEqualTo(2);
        assertThat(prepared.entries()).hasSize(8);
        assertThat(prepared.entries()).extracting(entry -> entry.mark().display())
                .doesNotContain("Ancient Dribblefrop", "Frumblecrast")
                .contains("Zibblefrax", "Zibblefrex", "Quonkleberry", "Wobblefrump Lantern Goods", "Plimbusto",
                        "Grumbleflux", "Snazzlefrop", "Mumblefrost Narwhal");
    }

    @Test
    void anExactMarkInATitleIsHighRisk() {
        for (String title : List.of("Zibblefrax Style Ceramic Mug", "Quonkleberry Jam Jar Label", "Plimbusto Poster",
                "Grumbleflux Mug", "Snazzlefrop Tee")) {
            TrademarkResult result = scan(title);

            assertThat(result.riskLevel()).as(title).isEqualTo(RiskLevel.HIGH);
            assertThat(ruleIds(result)).as(title).containsExactly("catalog_exact");
        }
    }

    @Test
    void aWholeMultiWordMarkIsHighRiskAsAPhrase() {
        for (String title : List.of("Wobblefrump Lantern Goods Candle", "Mumblefrost Narwhal Plush")) {
            TrademarkResult result = scan(title);

            assertThat(result.riskLevel()).as(title).isEqualTo(RiskLevel.HIGH);
            assertThat(ruleIds(result)).as(title).containsExactly("catalog_phrase");
        }
    }

    @Test
    void aNearMissIsMediumRiskAsAFuzzyMatch() {
        TrademarkResult result = scan("Zibblefrox Hoodie");

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ruleIds(result)).containsExactly("catalog_fuzzy");
    }

    @Test
    void theCancelledMarkTheMarkOutsideTheClassesAndAPartOfAPhraseAreNotFlagged() {
        for (String title : List.of("Ancient Dribblefrop Tote Bag", "Frumblecrast Desk Lamp", "Handmade Wobblefrump Lantern",
                "Plain ceramic mug")) {
            TrademarkResult result = scan(title);

            assertThat(result.riskLevel()).as(title).isEqualTo(RiskLevel.LOW);
            assertThat(result.findings()).as(title).isEmpty();
        }
    }
}
