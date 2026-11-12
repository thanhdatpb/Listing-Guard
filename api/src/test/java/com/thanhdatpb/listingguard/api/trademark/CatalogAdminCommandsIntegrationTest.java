package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.AdminCommands;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogHeader;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The admin job's {@code catalog list}, {@code catalog activate} and {@code catalog rollback} (ADR-009, PR A2d). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CatalogAdminCommandsIntegrationTest {

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    CatalogAdminCommands catalog;

    @Autowired
    TrademarkCatalogImporter importer;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    TrademarkCatalogLock lock;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @BeforeEach
    void emptyLog() {
        jdbc.execute("TRUNCATE trademark_catalog_activation");
    }

    private String run(String... args) {
        captured.reset();
        new AdminCommands(provisioning, catalog, new PrintStream(captured, true, StandardCharsets.UTF_8))
                .execute(new DefaultApplicationArguments(args));
        return captured.toString(StandardCharsets.UTF_8);
    }

    private CatalogHeader importCatalog() throws IOException {
        String label = "adm-" + UUID.randomUUID().toString().substring(0, 8);
        Path catalogDir = Files.createDirectories(dir.resolve(label));
        TrademarkFixtures.syntheticDir(catalogDir, label);
        return importer.importCatalog(CatalogManifest.load(catalogDir).entry(label), catalogDir.resolve("raw"),
                new TextNormalizer(rulesets.ruleset().normalization()), NormalizationIdentity.of(rulesets.ruleset()))
                .catalog();
    }

    @Test
    void listActivateAndRollBack() throws IOException {
        CatalogHeader first = importCatalog();
        CatalogHeader second = importCatalog();

        assertThat(run("catalog", "list")).contains(first.version()).contains(second.version())
                .contains("No catalog version has been activated.").contains("TRADEMARK_FOLLOW_ACTIVATION");

        assertThat(run("catalog", "activate", first.version(), "--note=reviewed by hand"))
                .contains("Catalog version " + first.version() + " activated");
        run("catalog", "activate", second.version());
        String listed = run("catalog", "list");
        assertThat(listed).containsPattern("\\* " + second.version()).doesNotContainPattern("\\* " + first.version());
        assertThat(listed).contains("MANUAL");

        assertThat(run("catalog", "rollback")).contains("Rolled back from " + second.version() + " to " + first.version())
                .contains("catalog activate");
        assertThat(run("catalog", "list")).containsPattern("\\* " + first.version()).contains("ROLLBACK");
        assertThat(run("catalog", "rollback", "--to=" + second.version()))
                .contains("to " + second.version());
    }

    @Test
    void refusedWhileASyncHoldsTheLock() throws IOException {
        CatalogHeader version = importCatalog();
        try (TrademarkCatalogLock.Held held = lock.tryAcquire().orElseThrow()) {
            assertThatThrownBy(() -> run("catalog", "activate", version.version()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("holds the catalog lock");
            assertThatThrownBy(() -> run("catalog", "rollback")).isInstanceOf(IllegalStateException.class);
            assertThat(run("catalog", "list")).as("reading needs no lock").contains(version.version());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_activation", Integer.class)).isZero();
    }

    @Test
    void aMissingArgumentShowsTheUsage() {
        assertThatThrownBy(() -> run("catalog", "activate")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing a catalog version").hasMessageContaining("catalog rollback [--to=<version>]");
        assertThatThrownBy(() -> run("catalog", "activate", "no-such-version-0123456789ab"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> new AdminCommands(provisioning, new PrintStream(captured, true, StandardCharsets.UTF_8))
                .execute(new DefaultApplicationArguments("catalog", "list")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not available");
    }
}
