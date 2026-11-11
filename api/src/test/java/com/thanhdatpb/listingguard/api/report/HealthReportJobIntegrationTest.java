package com.thanhdatpb.listingguard.api.report;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The {@code health-report} job (ADR-018) run as a class against a real database: it gives the same numbers as the endpoint,
 * writes {@code report.json} and a {@code report.html} that has no script and no unescaped text, names the clients it was
 * asked for, refuses what it cannot use before it writes anything, and never overwrites a directory. The profile, the command
 * line and the exit are in {@link HealthReportJobProfileTest}.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class HealthReportJobIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final String FROM = "2026-11-02";
    private static final String TO = "2026-11-08";
    private static final Instant CREATED = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant UNTIL = CREATED.plus(Duration.ofDays(30));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    HealthReportService service;

    @Autowired
    MutableClock clock;

    @TempDir
    Path out;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private HealthReportTestData data;

    @BeforeEach
    void atTheStartOfTheReportWeek() {
        clock.set(NOW);
        data = new HealthReportTestData(jdbc);
    }

    private HealthReportJob job() {
        return new HealthReportJob(clients, service, mapper, "Trademark results are reference warnings (test disclaimer).", out,
                new PrintStream(console, true, StandardCharsets.UTF_8), clock);
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private HealthReportOptions options(String client, String from, String to, String marketplace) {
        return new HealthReportOptions(client, false, from, to, marketplace);
    }

    private HealthReportOptions allClients(String from, String to, String marketplace) {
        return new HealthReportOptions(null, true, from, to, marketplace);
    }

    private void populate(TestClient client) {
        UUID listing = data.listing(client.id(), "ETSY", CREATED, HealthReportTestData.Readiness.scored(82, true));
        data.finding(listing, "ERROR", "title_length");
        data.finding(listing, "WARNING", "title_repeated_words");
        UUID trademark = data.trademark(client.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(trademark, 0, "catalog_exact", "HIGH", "ZORVEX");
        UUID variation = data.variation(client.id(), "ETSY", CREATED, UNTIL, "COMPLETE");
        data.variationFinding(variation, 0, "duplicate_sku", "WARNING");
        UUID screening = data.screening(client.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(screening, 0, "unsupported_characters", "WARNING");
    }

    private JsonNode json(Path dir) throws IOException {
        return mapper.readTree(Files.readString(dir.resolve("report.json"), StandardCharsets.UTF_8));
    }

    private static String html(Path dir) throws IOException {
        return Files.readString(dir.resolve("report.html"), StandardCharsets.UTF_8);
    }

    private JsonNode endpoint(TestClient client, String from, String to) throws Exception {
        return mapper.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/reports/summary").header(HEADER, client.key())
                        .param("from", from).param("to", to)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode withoutGeneratedAt(JsonNode report) {
        ObjectNode copy = report.deepCopy();
        copy.remove("generatedAt");
        return copy;
    }

    // ---- one service, so the same numbers

    @Test
    void theJobGivesTheNumbersTheEndpointGives() throws Exception {
        TestClient c = client();
        populate(c);

        HealthReportJob.Result result = job().run(options(c.name(), FROM, TO, null));

        JsonNode file = json(result.dir());
        assertThat(file.at("/clients")).hasSize(1);
        assertThat(file.at("/clients/0/client").asText()).isEqualTo(c.name());
        assertThat(file.at("/clients/0/clientId").asText()).isEqualTo(c.id().toString());
        assertThat(file.at("/period/from").asText()).isEqualTo(FROM);
        assertThat(file.at("/period/to").asText()).isEqualTo(TO);
        assertThat(file.at("/period/days").asInt()).isEqualTo(7);
        assertThat(file.at("/generatedAt").asText()).isEqualTo("2026-11-09T03:00:00Z");
        assertThat(withoutGeneratedAt(file.at("/clients/0/report"))).isEqualTo(withoutGeneratedAt(endpoint(c, FROM, TO)));
        assertThat(file.at("/clients/0/report/packs/listingRules/checks").asLong()).isEqualTo(1);
        assertThat(file.at("/clients/0/report/packs/trademarkRisk/topMarks/items/0/mark").asText()).isEqualTo("ZORVEX");
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("1 client(s)").contains(result.dir().toString());
    }

    @Test
    void theDirectoryIsNamedByTheUtcSecondAndHoldsBothFiles() throws Exception {
        TestClient c = client();

        HealthReportJob.Result result = job().run(options(c.name(), FROM, TO, null));

        assertThat(result.dir()).isEqualTo(out.resolve("20261109T030000Z"));
        assertThat(result.dir().resolve("report.json")).isRegularFile();
        assertThat(result.dir().resolve("report.html")).isRegularFile();
    }

    @Test
    void aClientIsReachedByNameOrById() throws Exception {
        TestClient c = client();
        populate(c);

        JsonNode byName = json(job().run(options(c.name(), FROM, TO, null)).dir());
        clock.set(NOW.plusSeconds(60));
        JsonNode byId = json(job().run(options(c.id().toString(), FROM, TO, null)).dir());

        assertThat(withoutGeneratedAt(byId.at("/clients/0/report"))).isEqualTo(withoutGeneratedAt(byName.at("/clients/0/report")));
        assertThat(byId.at("/clients/0/client").asText()).isEqualTo(c.name());
    }

    @Test
    void allIsEveryActiveClientWithOneEntryEach() throws Exception {
        TestClient a = client();
        TestClient b = client();
        TestClient off = client();
        populate(a);
        jdbc.update("UPDATE client SET active = FALSE WHERE id = ?", off.id());

        JsonNode file = json(job().run(allClients(FROM, TO, null)).dir());

        List<String> names = new ArrayList<>();
        file.at("/clients").forEach(entry -> names.add(entry.get("client").asText()));
        assertThat(names).contains(a.name(), b.name()).doesNotContain(off.name()).doesNotHaveDuplicates();
        assertThat(names).hasSize(jdbc.queryForObject("SELECT count(*) FROM client WHERE active", Integer.class));
        file.at("/clients").forEach(entry -> {
            if (entry.get("client").asText().equals(a.name())) {
                assertThat(entry.at("/report/packs/listingRules/checks").asLong()).isEqualTo(1);
            } else if (entry.get("client").asText().equals(b.name())) {
                assertThat(entry.at("/report/packs/listingRules/checks").asLong()).as("another client's numbers").isZero();
            }
        });
    }

    @Test
    void withoutADatesTheLastSevenCompleteDaysBeforeTodayAreReported() throws Exception {
        // 2026-11-09 is a Monday: the previous ISO week
        TestClient c = client();
        data.listing(c.id(), "ETSY", Instant.parse("2026-11-08T23:00:00Z"));
        data.listing(c.id(), "ETSY", Instant.parse("2026-11-09T01:00:00Z"));

        JsonNode file = json(job().run(options(c.name(), null, null, null)).dir());

        assertThat(file.at("/period/from").asText()).isEqualTo("2026-11-02");
        assertThat(file.at("/period/to").asText()).isEqualTo("2026-11-08");
        assertThat(file.at("/marketplace").isNull()).isTrue();
        assertThat(file.at("/clients/0/report/byWeek/0/isoWeek").asText()).isEqualTo("2026-W45");
        assertThat(file.at("/clients/0/report/byWeek/0/partial").asBoolean()).isFalse();
        assertThat(file.at("/clients/0/report/packs/listingRules/checks").asLong()).as("today is not counted").isEqualTo(1);

        clock.set(NOW.plusSeconds(60));
        JsonNode amazon = json(job().run(options(c.name(), null, null, "amazon")).dir());
        assertThat(amazon.at("/marketplace").asText()).isEqualTo("AMAZON");
        assertThat(amazon.at("/clients/0/report/packs/listingRules/checks").asLong()).as("both rows are ETSY").isZero();
    }

    // ---- refusals: nothing is written

    @Test
    void whatItCannotUseIsRefusedBeforeAnythingIsWritten() {
        TestClient inactive = client();
        jdbc.update("UPDATE client SET active = FALSE WHERE id = ?", inactive.id());

        assertThatThrownBy(() -> job().run(options("nobody-by-this-name", FROM, TO, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No client named or with the id 'nobody-by-this-name'");
        assertThatThrownBy(() -> job().run(options(UUID.randomUUID().toString(), FROM, TO, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No client named or with the id");
        assertThatThrownBy(() -> job().run(options(inactive.name(), FROM, TO, null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is inactive");
        assertThatThrownBy(() -> job().run(options(inactive.id().toString(), FROM, TO, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is inactive");
        assertThatThrownBy(() -> job().run(options(client().name(), TO, FROM, null))).isInstanceOf(InvalidReportPeriodException.class);
        assertThatThrownBy(() -> job().run(options(client().name(), "2026-01-01", "2026-04-03", null)))
                .isInstanceOf(ReportPeriodTooLongException.class);
        assertThatThrownBy(() -> job().run(options(client().name(), FROM, TO, "nope"))).isInstanceOf(UnknownMarketplaceException.class);

        assertThat(out).isEmptyDirectory();
    }

    @Test
    void aDirectoryThatExistsIsNeverOverwritten() throws Exception {
        TestClient c = client();
        Path first = job().run(options(c.name(), FROM, TO, null)).dir();
        String before = Files.readString(first.resolve("report.json"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> job().run(options(c.name(), FROM, TO, null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        assertThat(Files.readString(first.resolve("report.json"), StandardCharsets.UTF_8)).isEqualTo(before);
        try (Stream<Path> children = Files.list(out)) {
            assertThat(children.count()).isEqualTo(1);
        }
    }

    // ---- report.html

    @Test
    void theHtmlHasNoScriptNoLinkAndEscapesEveryString() throws Exception {
        TestClient c = client();
        UUID listing = data.listing(c.id(), "ETSY", CREATED);
        data.finding(listing, "ERROR", "<script>alert(1)</script>");
        UUID trademark = data.trademark(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(trademark, 0, "catalog_exact", "HIGH", "<img onerror=alert(1)>");
        UUID variation = data.variation(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE");
        data.variationFinding(variation, 0, "\"><script>alert(2)</script>", "WARNING");
        UUID screening = data.screening(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(screening, 0, "'&<b>bold</b>", "WARNING");

        String html = html(job().run(options(c.name(), FROM, TO, null)).dir());

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;img onerror=alert(1)&gt;")
                .contains("&quot;&gt;&lt;script&gt;alert(2)&lt;/script&gt;").contains("&#39;&amp;&lt;b&gt;bold&lt;/b&gt;");
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<link").doesNotContain("<iframe")
                .doesNotContain(" src=").doesNotContain(" href=").doesNotContain("http://").doesNotContain("https://")
                .doesNotContain("url(").doesNotContain("<b>bold</b>");
        assertThat(html).contains("default-src 'none'");
        Set<String> allowed = Set.of("html", "head", "meta", "title", "style", "body", "main", "section", "h1", "h2", "h3", "p", "b",
                "time", "table", "tr", "th", "td", "div", "code");
        Matcher tag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)").matcher(html);
        while (tag.find()) {
            assertThat(allowed).as("the tag <" + tag.group(1) + ">").contains(tag.group(1));
        }
    }

    @Test
    void theHtmlIsInVietnameseAndSaysWhatItCovers() throws Exception {
        TestClient c = client();
        populate(c);

        String html = html(job().run(options(c.name(), FROM, TO, null)).dir());

        assertThat(html).contains("<html lang=\"vi\">").contains("Báo cáo sức khỏe listing").contains("Client <b>" + c.name() + "</b>")
                .contains("2026-11-02").contains("2026-11-08").contains("2026-W45")
                .contains("Trademark results are reference warnings (test disclaimer).")
                .contains("kết quả đã lưu").contains("UNKNOWN (chưa xác định, không phải sạch)").contains("ZORVEX")
                .contains("Mọi pack đều còn đủ số liệu");
    }

    @Test
    void aPeriodOlderThanTheRetentionIsFlaggedInTheHtmlAndTheJson() throws Exception {
        TestClient c = client();

        Path dir = job().run(options(c.name(), "2026-10-01", "2026-10-31", null)).dir();

        assertThat(html(dir)).contains("Số liệu chưa đủ cho cả khoảng ngày").contains("trademark-risk (lưu P30D, đủ số liệu từ ngày 2026-10-11)");
        assertThat(json(dir).at("/clients/0/report/coverageNote/complete").asBoolean()).isFalse();
    }

    @Test
    void noTextAClientTypedAndNoCheckIdIsInEitherFile() throws Exception {
        String secret = "SECRET-TYPED-TEXT-5519";
        TestClient c = client();
        UUID listing = data.listing(c.id(), "ETSY", CREATED);
        data.finding(listing, "ERROR", "title_length", secret);
        UUID trademark = data.trademark(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "HIGH");
        data.trademarkFinding(trademark, 0, "catalog_exact", "HIGH", "ZORVEX", secret);
        UUID variation = data.variation(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE");
        data.variationFinding(variation, 0, "duplicate_sku", "WARNING", secret);
        UUID screening = data.screening(c.id(), "ETSY", CREATED, UNTIL, "COMPLETE", "COMPLETE", "LOW");
        data.screeningFinding(screening, 0, "unsupported_characters", "WARNING", secret);

        Path dir = job().run(options(c.name(), FROM, TO, null)).dir();

        String both = Files.readString(dir.resolve("report.json"), StandardCharsets.UTF_8) + html(dir);
        assertThat(both).doesNotContain(secret).contains("title_length").contains("ZORVEX");
        for (UUID id : List.of(listing, trademark, variation, screening)) {
            assertThat(both).doesNotContain(id.toString());
        }
    }
}
