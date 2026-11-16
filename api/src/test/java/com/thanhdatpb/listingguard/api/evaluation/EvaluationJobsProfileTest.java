package com.thanhdatpb.listingguard.api.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The two one-shot jobs of ADR-032 as an operator runs them: the profile, the command line, the default report directory, and
 * how they end. The backtest stores nothing and needs no client; with no catalog configured it still runs the rules.
 */
@Testcontainers
class EvaluationJobsProfileTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path dir;

    private void runJob(String profile, String... arguments) {
        String[] all = new String[arguments.length + 3];
        all[0] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        all[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        all[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        System.arraycopy(arguments, 0, all, 3, arguments.length);
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE)
                .profiles(profile).run(all).close();
    }

    @Test
    void theBacktestRunsAsAJobAndWritesItsReportToTheDefaultDirectory() throws IOException {
        assertThatThrownBy(() -> runJob("backtest", "--listing-guard.evaluation.backtest-dir=" + dir))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing --input")
                .hasMessageContaining("--claimed-column");

        Path input = Files.writeString(dir.resolve("claims.csv"), "sku,title,claimed,issue\n"
                + "J-1,Plain ceramic mug,yes,listing-policy\nJ-2,Plain ceramic mug,no,\n", StandardCharsets.UTF_8);
        Path mapping = Files.writeString(dir.resolve("mapping.yaml"), "id: sku\ntitle: title\n", StandardCharsets.UTF_8);

        runJob("backtest", "--listing-guard.evaluation.backtest-dir=" + dir, "--input=" + input, "--mapping=" + mapping,
                "--marketplace=ETSY", "--claimed-column=claimed", "--issue-column=issue");

        Path report;
        try (Stream<Path> runs = Files.list(dir.resolve("out"))) {
            report = runs.findFirst().orElseThrow().resolve("report.json");
        }
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(json).contains("\"trademarkState\" : \"DISABLED\"").contains("\"total\" : 2");
    }

    @Test
    void theLabelledEvaluationRunsAsAJobAgainstTheCommittedSet() throws IOException {
        Path listing = dir.resolve("listing");
        Files.createDirectories(listing.resolve("cases"));
        try (Stream<Path> cases = Files.list(Path.of("..", "eval", "listing", "cases"))) {
            for (Path file : cases.toList()) {
                Files.copy(file, listing.resolve("cases").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("..", "eval", "listing", "baseline.json"), listing.resolve("baseline.json"));

        runJob("eval-listing", "--listing-guard.evaluation.listing-dir=" + listing,
                "--listing-guard.evaluation.demo-catalog-dir=" + Path.of("..", "catalogs", "demo"));

        assertThat(Files.readString(listing.resolve("out").resolve("report.md"), StandardCharsets.UTF_8))
                .contains("Gate: passed");
        assertThatThrownBy(() -> runJob("eval-listing", "--listing-guard.evaluation.listing-dir=" + listing, "--bogus"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown option --bogus");
    }
}
