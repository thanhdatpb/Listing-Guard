package com.thanhdatpb.listingguard.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The one-shot job {@code health-report} as an operator runs it: the profile, the command line, the directory it writes to, and
 * the way it ends (it returns on success and throws, so the process exits non-zero, on a refusal).
 */
@Testcontainers
class HealthReportJobProfileTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path dir;

    private void runJob(String... arguments) {
        String[] all = new String[arguments.length + 4];
        all[0] = "--spring.datasource.url=" + POSTGRES.getJdbcUrl();
        all[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        all[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        all[3] = "--listing-guard.health-report.dir=" + dir;
        System.arraycopy(arguments, 0, all, 4, arguments.length);
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE)
                .profiles("health-report").run(all).close();
    }

    private void sql(String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = connection.createStatement()) {
            s.execute(statement);
        }
    }

    private List<Path> runDirectories() throws IOException {
        if (!Files.exists(dir.resolve("out"))) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir.resolve("out"))) {
            return children.sorted().toList();
        }
    }

    @Test
    void runsAsAJobWritesUnderTheConfiguredDirectoryAndRefusesWhatItCannotUse() throws IOException, SQLException {
        // the first start applies the migrations, and it ends on the missing option
        assertThatThrownBy(() -> runJob()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pass exactly one of --client")
                .hasMessageContaining("--all-clients");

        sql("INSERT INTO client (name) VALUES ('report-client')");
        sql("INSERT INTO client (name) VALUES ('report-other')");
        sql("INSERT INTO client (name, active) VALUES ('report-off', false)");

        assertThatThrownBy(() -> runJob("--client=report-client", "--bogus=1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown option --bogus");
        assertThatThrownBy(() -> runJob("--client=report-client", "--from=2026-11-02")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--from and --to together");
        assertThatThrownBy(() -> runJob("--client=nobody")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No client named or with the id 'nobody'");
        assertThatThrownBy(() -> runJob("--client=report-off")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is inactive");
        assertThatThrownBy(() -> runJob("--client=report-client", "--from=2026-11-08", "--to=2026-11-02"))
                .isInstanceOf(InvalidReportPeriodException.class);
        assertThatThrownBy(() -> runJob("--client=report-client", "--from=2026-01-01", "--to=2026-04-03"))
                .isInstanceOf(ReportPeriodTooLongException.class);
        assertThat(runDirectories()).as("a refusal writes nothing").isEmpty();

        runJob("--client=report-client");

        List<Path> runs = runDirectories();
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getFileName().toString()).matches("\\d{8}T\\d{6}Z");
        JsonNode file = new ObjectMapper().readTree(Files.readString(runs.get(0).resolve("report.json"), StandardCharsets.UTF_8));
        assertThat(file.at("/clients")).hasSize(1);
        assertThat(file.at("/clients/0/client").asText()).isEqualTo("report-client");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        assertThat(file.at("/period/days").asInt()).isEqualTo(7);
        assertThat(file.at("/period/to").asText()).as("the default period ends yesterday").isIn(yesterday.toString(),
                yesterday.minusDays(1).toString());
        assertThat(file.at("/clients/0/report/versions/trademarkCatalog/state").asText()).isEqualTo("DISABLED");
        assertThat(file.at("/clients/0/report/versions/trademarkCatalog/version").isNull()).isTrue();
        assertThat(file.at("/clients/0/report/packs/listingRules/checks").asLong()).isZero();
        assertThat(Files.readString(runs.get(0).resolve("report.html"), StandardCharsets.UTF_8)).contains("report-client");
    }

    @Test
    void allWritesOneEntryForEveryActiveClientAndTheMarketplaceIsKept() throws IOException, SQLException {
        // any start applies the migrations, so a refused run creates the schema when this method runs first
        assertThatThrownBy(() -> runJob("--client=nobody-yet")).isInstanceOf(IllegalArgumentException.class);
        sql("INSERT INTO client (name) VALUES ('all-a')");
        sql("INSERT INTO client (name) VALUES ('all-b')");
        sql("INSERT INTO client (name, active) VALUES ('all-off', false)");
        sql("INSERT INTO client (name) VALUES ('all') ON CONFLICT (name) DO NOTHING");

        runJob("--all-clients", "--from=2026-11-02", "--to=2026-11-08", "--marketplace=etsy");

        List<Path> runs = runDirectories();
        assertThat(runs).hasSize(1);
        JsonNode file = new ObjectMapper().readTree(Files.readString(runs.get(0).resolve("report.json"), StandardCharsets.UTF_8));
        List<String> names = new java.util.ArrayList<>();
        file.at("/clients").forEach(entry -> names.add(entry.get("client").asText()));
        assertThat(names).contains("all-a", "all-b", "all").doesNotContain("all-off");
        assertThat(file.at("/marketplace").asText()).isEqualTo("ETSY");
        assertThat(file.at("/period/from").asText()).isEqualTo("2026-11-02");
        assertThat(file.at("/period/to").asText()).isEqualTo("2026-11-08");
    }

    @Test
    void aClientNamedAllCanBeSelectedByName() throws IOException, SQLException {
        assertThatThrownBy(() -> runJob("--client=not-created")).isInstanceOf(IllegalArgumentException.class);
        sql("INSERT INTO client (name) VALUES ('all') ON CONFLICT (name) DO NOTHING");

        runJob("--client=all");

        List<Path> runs = runDirectories();
        assertThat(runs).hasSize(1);
        JsonNode file = new ObjectMapper().readTree(Files.readString(runs.get(0).resolve("report.json"), StandardCharsets.UTF_8));
        assertThat(file.at("/clients")).hasSize(1);
        assertThat(file.at("/clients/0/client").asText()).isEqualTo("all");
    }
}
