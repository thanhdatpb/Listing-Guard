package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ListingGuardApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole path of a USPTO file (ADR-009, PR A2b): the {@code uspto-convert} job turns a small synthetic daily file
 * into {@code jsonl-1}, the {@code trademark-import} job loads it from a manifest that names its {@code sha256}, and a
 * running API answers {@code POST /api/v1/trademark-checks} with a finding that points at the converted record. Every
 * mark and serial number is invented, and the XML was written by hand to follow the public DTD V2.0: this proves the
 * pieces fit, not that a real USPTO file converts.
 */
@Testcontainers
class UsptoConvertEndToEndIntegrationTest {

    private static final String DEV_KEY = "end-to-end-test-key-0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path dir;

    private static String caseFile(String serial, String mark, String drawing, String status, String niceClass) {
        return "<case-file><serial-number>" + serial + "</serial-number><registration-number>0000000</registration-number>"
                + "<case-file-header><filing-date>20200101</filing-date><status-code>" + status + "</status-code>"
                + "<mark-identification>" + mark + "</mark-identification><mark-drawing-code>" + drawing
                + "</mark-drawing-code></case-file-header><classifications><classification>"
                + "<international-code>" + niceClass + "</international-code></classification></classifications>"
                + "</case-file>";
    }

    private static String dailyFile() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trademark-applications-daily><version>"
                + "<version-no>2.0</version-no><version-date>20041108</version-date></version><application-information>"
                + "<file-segments><file-segment>TRMK</file-segment><action-keys><action-key>NA</action-key>"
                + caseFile("97000001", "Zorvex", "4000", "700", "025")        // kept: registered
                + caseFile("97000002", "Brasktone", "5R12", "686", "035")     // kept: published for opposition
                + caseFile("97000003", "Quillfeather", "2000", "700", "025")  // dropped: a logo without words
                + caseFile("97000004", "Marlowick", "4000", "973", "025")     // dropped: no status of the mark
                + "</action-keys></file-segments></application-information></trademark-applications-daily>";
    }

    private void runJob(String profile, String... arguments) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--listing-guard.trademark.catalogs-dir=" + dir));
        all.addAll(List.of(arguments));
        new SpringApplicationBuilder(ListingGuardApplication.class).web(WebApplicationType.NONE).profiles(profile)
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

    private static JsonNode finding(JsonNode result, String markText) {
        for (JsonNode finding : result.get("findings")) {
            JsonNode mark = finding.get("mark");
            if (mark != null && !mark.isNull() && mark.asText().equalsIgnoreCase(markText)) {
                return finding;
            }
        }
        return null;
    }

    private JsonNode check(HttpClient client, int port, String title) throws Exception {
        ObjectNode listing = JSON.createObjectNode();
        listing.put("title", title);
        listing.put("description", "A plain description of a handmade item for the end-to-end test.");
        ArrayNode tags = listing.putArray("tags");
        tags.add("handmade");
        ObjectNode body = JSON.createObjectNode();
        body.put("marketplace", "ETSY");
        body.set("listing", listing);

        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/trademark-checks")).header("X-API-Key", DEV_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    void aConvertedFileImportsAndTheApiReportsItsMarks() throws Exception {
        Path raw = Files.createDirectories(dir.resolve("raw").resolve("uspto"));
        Files.writeString(raw.resolve("daily-synthetic.xml"), dailyFile(), StandardCharsets.UTF_8);

        // 1. convert
        runJob("uspto-convert", "--input=uspto/daily-synthetic.xml", "--output=uspto-e2e.jsonl");
        Path jsonl = dir.resolve("raw").resolve("uspto-e2e.jsonl");
        assertThat(Files.readAllLines(jsonl)).containsExactly(
                "{\"mark\":\"Zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000001\"}",
                "{\"mark\":\"Brasktone\",\"status\":\"PENDING\",\"nice_classes\":[35],\"source_id\":\"97000002\"}");
        JsonNode report = JSON.readTree(Files.readString(dir.resolve("raw").resolve("uspto-e2e.jsonl.report.json")));
        assertThat(report.get("recordsRead").asInt()).isEqualTo(4);
        assertThat(report.get("recordsWritten").asInt()).isEqualTo(2);
        assertThat(report.get("dropped").get("logo_only").asInt()).isEqualTo(1);
        assertThat(report.get("dropped").get("status_dropped:973").asInt()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM trademark_catalog_version")).as("the job writes no catalog").isEqualTo("0");

        // 2. import, with the sha256 the job printed
        Files.writeString(dir.resolve("manifest.yaml"), """
                catalogs:
                  - version: uspto-e2e
                    format_version: jsonl-1
                    sources:
                      - source_key: uspto
                        file: uspto-e2e.jsonl
                        source_name: Synthetic USPTO-shaped file
                        url: https://example.invalid/uspto
                        license: test only
                        source_date: 2026-11-05
                        sha256: %s
                    filters:
                      include_classes: [25, 35]
                      include_status: [REGISTERED, PENDING]
                """.formatted(report.get("outputSha256").asText()));
        runJob("trademark-import", "--catalog=uspto-e2e");
        String version = scalar("SELECT version FROM trademark_catalog_version WHERE label = 'uspto-e2e'");
        assertThat(scalar("SELECT records_loaded FROM trademark_catalog_version WHERE label = 'uspto-e2e'")).isEqualTo("2");

        // 3. the API serves that catalog and answers a check
        try (ConfigurableApplicationContext api = new SpringApplicationBuilder(ListingGuardApplication.class)
                .web(WebApplicationType.SERVLET).profiles("dev")
                .run("--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--listing-guard.security.dev-api-key=" + DEV_KEY,
                        "--listing-guard.trademark.catalog-version=" + version)) {
            int port = Integer.parseInt(api.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newHttpClient();

            JsonNode result = check(client, port, "Handmade Zorvex hoodie for kids");

            assertThat(result.get("catalogVersion").asText()).isEqualTo(version);
            JsonNode exact = finding(result, "Zorvex");
            assertThat(exact).as("a finding for the converted mark in %s", result.get("findings")).isNotNull();
            assertThat(exact.get("ruleId").asText()).isEqualTo("catalog_exact");
            List<String> sourceIds = new ArrayList<>();
            exact.get("sourceIds").forEach(id -> sourceIds.add(id.asText()));
            assertThat(sourceIds).containsExactly("uspto:97000001");

            // the records the converter dropped are not in the catalog
            JsonNode dropped = check(client, port, "Quillfeather and Marlowick fabric collection");
            assertThat(finding(dropped, "Quillfeather")).isNull();
            assertThat(finding(dropped, "Marlowick")).isNull();
        }
    }
}
