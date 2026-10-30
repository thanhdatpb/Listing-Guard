package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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

/**
 * The one-shot job {@code batch-check} as an operator runs it: the profile, the command line, the default report
 * directory, and the way it ends (it returns on success and throws, so the process exits non-zero, on a refusal).
 */
@Testcontainers
class BatchCheckJobProfileTest {

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
        all[3] = "--listing-guard.batch.dir=" + dir;
        System.arraycopy(arguments, 0, all, 4, arguments.length);
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE)
                .profiles("batch-check").run(all).close();
    }

    private void sql(String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = connection.createStatement()) {
            s.execute(statement);
        }
    }

    @Test
    void runsAsAJobWritesToTheDefaultDirectoryAndRefusesWhatItCannotUse() throws IOException, SQLException {
        // the first start applies the migrations, and it ends on the missing options
        assertThatThrownBy(() -> runJob()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing --input").hasMessageContaining("--marketplace=ETSY|AMAZON");

        sql("INSERT INTO client (name) VALUES ('job-client')");
        sql("INSERT INTO client (name, active) VALUES ('job-off', false)");
        Path input = Files.writeString(dir.resolve("listings.tsv"), "﻿sku\ttitle\ttags\n"
                + "J-1\tPlain mug\tmug|ceramic\n", StandardCharsets.UTF_8);
        Path mapping = Files.writeString(dir.resolve("mapping.yaml"), """
                csv: {delimiter: tab}
                id: sku
                title: title
                tags: {column: tags, separator: "|"}
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> runJob("--input=" + input, "--mapping=" + mapping, "--client=job-client", "--marketplace=ETSY",
                "--bogus=1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown option --bogus");
        assertThatThrownBy(() -> runJob("--input=" + input, "--mapping=" + mapping, "--client=nobody", "--marketplace=ETSY"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No client named 'nobody'");
        assertThatThrownBy(() -> runJob("--input=" + input, "--mapping=" + mapping, "--client=job-off", "--marketplace=ETSY"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is inactive");
        assertThatThrownBy(() -> runJob("--input=" + input, "--mapping=" + mapping, "--client=job-client", "--marketplace=ETSY",
                "--max-rows=0")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--max-rows");
        assertThat(dir.resolve("out")).doesNotExist();

        runJob("--input=" + input, "--mapping=" + mapping, "--client=job-client", "--marketplace=etsy");

        Path out = dir.resolve("out");
        assertThat(out.resolve("findings.csv")).exists();
        assertThat(out.resolve("summary.html")).exists();
        String listings = Files.readString(out.resolve("listings.csv"), StandardCharsets.UTF_8);
        assertThat(listings.lines().count()).isEqualTo(2);
        assertThat(listings).contains("J-1,2,OK,,NOT_CONFIGURED");
    }
}
