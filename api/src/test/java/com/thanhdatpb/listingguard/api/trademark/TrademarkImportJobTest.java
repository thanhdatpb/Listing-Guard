package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogFormatException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The one-shot job {@code trademark-import}: local files in, a catalog version in the database, then it ends. */
@Testcontainers
class TrademarkImportJobTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path dir;

    private void runJob(String... arguments) {
        String[] all = new String[arguments.length + 4];
        all[0] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        all[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        all[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        all[3] = "--listing-guard.trademark.catalogs-dir=" + dir;
        System.arraycopy(arguments, 0, all, 4, arguments.length);
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE)
                .profiles("trademark-import").run(all).close();
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    void importsTheCatalogNamedOnTheCommandLineAndRunsAgainWithoutChange() throws SQLException {
        TrademarkFixtures.syntheticDir(dir, "job-set");

        runJob("--catalog=job-set");

        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version WHERE label = 'job-set'")).isEqualTo("1");
        assertThat(scalar("SELECT records_loaded FROM trademark_catalog_version WHERE label = 'job-set'")).isEqualTo("7");
        assertThat(scalar("SELECT count(*) FROM trademark_entry e JOIN trademark_catalog_version v "
                + "ON v.id = e.catalog_version_id WHERE v.label = 'job-set'")).isEqualTo("7");

        runJob("--catalog=job-set");

        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version WHERE label = 'job-set'")).isEqualTo("1");
    }

    @Test
    void failsWhenNoCatalogIsNamedOrItIsNotInTheManifest() {
        TrademarkFixtures.syntheticDir(dir, "job-named");

        assertThatThrownBy(() -> runJob()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--catalog=<version>").hasMessageContaining("job-named");
        assertThatThrownBy(() -> runJob("--catalog=other")).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("no catalog 'other'");
    }

    @Test
    void refusesAFileWhoseHashDiffersFromTheManifestAndWritesNothing() throws Exception {
        TrademarkFixtures.syntheticDir(dir, "job-hash");
        Files.writeString(dir.resolve("raw").resolve("synthetic.jsonl"),
                "{\"mark\":\"Changed\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"1\"}\n");

        assertThatThrownBy(() -> runJob("--catalog=job-hash")).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("SHA-256");

        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version WHERE label = 'job-hash'")).isEqualTo("0");
    }

    @Test
    void refusesABadLineWithItsNumberAndWritesNothing() throws Exception {
        String data = TrademarkFixtures.resource(TrademarkFixtures.SYNTHETIC)
                + "{\"mark\":\"Bad Status\",\"status\":\"FAMOUS\",\"nice_classes\":[25],\"source_id\":\"1\"}\n";
        TrademarkFixtures.catalogsDir(dir, "job-bad", data, "[25]", "[REGISTERED]");

        assertThatThrownBy(() -> runJob("--catalog=job-bad")).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("line 12").hasMessageContaining("unknown status");

        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version WHERE label = 'job-bad'")).isEqualTo("0");
    }

    @Test
    void aMissingManifestFailsTheJob() {
        assertThatThrownBy(() -> runJob("--catalog=anything")).isInstanceOf(CatalogFormatException.class).hasStackTraceContaining("Cannot read the catalog manifest");
    }

    @Test
    void theJobNeedsNoNetworkAndNoEmbeddingProvider() throws SQLException {
        TrademarkFixtures.syntheticDir(dir, "job-offline");
        // the embedding provider is the default (none): importing must not need one, and there is no URL to reach
        runJob("--catalog=job-offline", "--listing-guard.embedding.provider=none");

        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version WHERE label = 'job-offline'")).isEqualTo("1");
    }
}
