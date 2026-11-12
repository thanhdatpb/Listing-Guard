package com.thanhdatpb.listingguard.api.trademark.sync;

import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.live;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.write;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.xml;
import static com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncTestFiles.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@code uspto-sync} profile as an operator runs it (ADR-009, PR A2d): the command line, the settings from the
 * environment, a refusal without the terms, and an offline backfile that ends activated. The download stays off.
 */
@Testcontainers
class UsptoSyncJobProfileTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path catalogs;

    private void run(String... arguments) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--listing-guard.trademark.catalogs-dir=" + catalogs,
                "--listing-guard.trademark.uspto.sync.min-free-disk=1MB"));
        all.addAll(List.of(arguments));
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE).profiles("uspto-sync")
                .run(all.toArray(String[]::new)).close();
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
    void theJobRegistersABackfileAndActivatesItsCatalog() throws Exception {
        String name = "apc18840407-20251231-01.zip";
        write(catalogs.resolve("raw/inbox"), name, zip(name, xml(live("90000001", "20251201", "Zorvex"))));

        // Spring Boot 4 hands the runner's exception through as it is
        assertThatThrownBy(() -> run("--input-dir=inbox", "--backfile")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USPTO_SYNC_LICENSE");
        assertThatThrownBy(() -> run("stray-argument")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("options only");

        run("--input-dir=inbox", "--backfile", "--listing-guard.trademark.uspto.sync.catalog.license=test only");

        assertThat(scalar("SELECT status FROM uspto_sync_run ORDER BY id DESC LIMIT 1")).isEqualTo("ACTIVATED");
        assertThat(scalar("SELECT reason FROM trademark_catalog_activation ORDER BY id DESC LIMIT 1")).isEqualTo("SYNC");
        assertThat(scalar("SELECT source_license FROM trademark_catalog_version ORDER BY id DESC LIMIT 1"))
                .isEqualTo("uspto: test only");
        assertThat(scalar("SELECT count(*) FROM uspto_sync_file WHERE status = 'STORED'")).isEqualTo("1");
    }
}
