package com.thanhdatpb.listingguard.api.batch;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_2;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The batch job against the real services and schema, with the synthetic trademark catalog READY: its results are the
 * ones the API gives for the same listing and are stored under the named client, a row that cannot be read does not stop
 * the batch, an unknown or inactive client is refused, the label measurement matches an answer worked out by hand and a
 * second run writes the same files.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class BatchCheckJobIntegrationTest {

    static final Instant RUN_1 = Instant.parse("2026-10-30T09:15:30Z");
    static final Instant RUN_2 = Instant.parse("2026-11-02T17:45:10Z");

    static final String ETSY_MAPPING = """
            id: sku
            title: Title
            description: Description
            tags: {column: Tags, separator: ","}
            quantity: Qty
            price: Price
            who_made: WhoMade
            when_made: WhenMade
            """;

    static final String ETSY_HEADER = "sku,Title,Description,Tags,Qty,Price,WhoMade,WhenMade,Takedown";

    static final String AMAZON_MAPPING = """
            id: asin
            title: item_name
            brand: brand_name
            bullet_points: [bp1, bp2, bp3]
            search_terms: keywords
            product_type: ptype
            """;

    static final String AMAZON_HEADER = "asin,item_name,brand_name,bp1,bp2,bp3,keywords,ptype";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClientRepository clients;

    @Autowired
    NormalizerRegistry normalizers;

    @Autowired
    CheckService checks;

    @Autowired
    TrademarkCheckService trademarkChecks;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    TrademarkRulesets rulesets;

    @Autowired
    ClientProvisioning provisioning;

    @TempDir
    Path dir;

    // ---- helpers

    /** Letters only, so the text can never look like a catalog mark. */
    static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.toString();
    }

    /** A CSV cell, quoted. */
    static String q(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    BatchCheckJob job(ByteArrayOutputStream console, Instant at) {
        return new BatchCheckJob(clients, normalizers, checks, trademarkChecks, runtime, rulesets,
                new PrintStream(console, true, StandardCharsets.UTF_8), Clock.fixed(at, ZoneOffset.UTC));
    }

    BatchCheckJob job() {
        return job(new ByteArrayOutputStream(), RUN_1);
    }

    Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    BatchOptions options(String client, String marketplace, Path input, Path mapping, String out, Integer maxRows,
                         String labelColumn) {
        return new BatchOptions(input, mapping, client, marketplace, dir.resolve(out), maxRows, labelColumn);
    }

    static List<Map<String, String>> csv(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        List<Map<String, String>> rows = new ArrayList<>();
        try (MappingIterator<Map<String, String>> it = new CsvMapper().readerFor(Map.class)
                .with(CsvSchema.emptySchema().withHeader()).readValues(text.substring(1))) {
            while (it.hasNext()) {
                rows.add(it.next());
            }
        }
        return rows;
    }

    private static Map<String, String> listing(List<Map<String, String>> listings, String id) {
        return listings.stream().filter(row -> row.get("id").equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no listing " + id + " in " + listings));
    }

    private JsonNode api(String path, String marketplace, ObjectNode listing) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", marketplace);
        body.set("listing", listing);
        String response = mvc.perform(post(path).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    /** What the API answers for the listing the batch built from a row; the batch must have stored the same results. */
    private void assertSameAsApi(String marketplace, ObjectNode json, String id, Path report) throws Exception {
        Map<String, String> listing = listing(csv(report.resolve("listings.csv")), id);
        List<Map<String, String>> findings = csv(report.resolve("findings.csv")).stream()
                .filter(row -> row.get("id").equals(id)).toList();

        JsonNode rules = api("/api/v1/checks", marketplace, json);
        assertThat(rules.get("cached").asBoolean()).as("the API is answered from what the batch stored").isTrue();
        assertThat(rules.get("checkId").asText()).isEqualTo(listing.get("rulesCheckId"));
        assertThat(rules.get("summary").get("errors").asText()).isEqualTo(listing.get("ruleErrors"));
        assertThat(rules.get("summary").get("warnings").asText()).isEqualTo(listing.get("ruleWarnings"));
        List<List<String>> ruleRows = new ArrayList<>();
        for (JsonNode finding : rules.get("findings")) {
            ruleRows.add(List.of(finding.get("ruleId").asText(), finding.get("severity").asText(), finding.get("field").asText(),
                    finding.get("value").isNull() ? "" : BatchReport.clip(finding.get("value").asText()),
                    finding.get("message").asText(), finding.get("suggestion").asText()));
        }
        assertThat(ruleRows).isNotEmpty();
        assertThat(rows(findings, "rules", "ruleId", "severityRisk", "field", "snippet", "message", "suggestion"))
                .isEqualTo(ruleRows);

        JsonNode tm = api("/api/v1/trademark-checks", marketplace, json);
        assertThat(tm.get("cached").asBoolean()).isTrue();
        assertThat(tm.get("checkId").asText()).isEqualTo(listing.get("trademarkCheckId"));
        assertThat(tm.get("riskLevel").asText()).isEqualTo(listing.get("trademarkRisk"));
        assertThat(tm.get("completeness").asText()).isEqualTo(listing.get("completeness"));
        List<List<String>> markRows = new ArrayList<>();
        for (JsonNode finding : tm.get("findings")) {
            List<String> sources = new ArrayList<>();
            finding.get("sourceIds").forEach(source -> sources.add(source.asText()));
            markRows.add(List.of(finding.get("ruleId").asText(), finding.get("risk").asText(), finding.get("field").asText(),
                    BatchReport.clip(finding.get("snippet").asText()),
                    finding.get("mark").isNull() ? "" : finding.get("mark").asText(), String.join("|", sources),
                    finding.get("message").asText(), finding.get("suggestion").asText()));
        }
        assertThat(markRows).isNotEmpty();
        assertThat(rows(findings, "trademark", "ruleId", "severityRisk", "field", "snippet", "mark", "sourceIds", "message",
                "suggestion")).isEqualTo(markRows);
    }

    private static List<List<String>> rows(List<Map<String, String>> findings, String check, String... columns) {
        return findings.stream().filter(row -> row.get("check").equals(check))
                .map(row -> java.util.Arrays.stream(columns).map(row::get).collect(Collectors.toList())).toList();
    }

    private ObjectNode etsyJson(String title, String description, String[] tags, int quantity, String price) {
        ObjectNode json = mapper.createObjectNode();
        json.put("title", title);
        json.put("description", description);
        ArrayNode tagNodes = json.putArray("tags");
        for (String tag : tags) {
            tagNodes.add(tag);
        }
        json.put("quantity", quantity);
        json.put("price", price);
        json.put("who_made", "i_did");
        json.put("when_made", "made_to_order");
        return json;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- the same result as the API

    @Test
    void anEtsyBatchGivesTheResultsTheApiGivesForTheSameListing() throws Exception {
        String title = "Zorvex Style " + unique() + " Baseball Jersey";
        String description = "Plain description " + unique();
        String clean = unique();
        Path input = file("etsy.csv", ETSY_HEADER + "\n"
                + "E-1," + q(title) + "," + q(description) + "," + q("jersey, jersey ,royal blue white pinstripe") + ",5,19.99,i_did,made_to_order,\n"
                + "E-2," + clean + "," + q("Plain description " + unique()) + ",mug,3,10.00,i_did,made_to_order,\n");
        Path mapping = file("etsy.yaml", ETSY_MAPPING);

        BatchCheckJob.Result result = job().run(options(CLIENT_1, "ETSY", input, mapping, "etsy-out", null, null));

        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.unreadableRows()).isZero();
        assertThat(result.failedRows()).isZero();
        Path report = dir.resolve("etsy-out");
        assertThat(report.resolve("findings.csv")).exists();
        assertThat(report.resolve("listings.csv")).exists();
        assertThat(report.resolve("summary.html")).exists();
        assertSameAsApi("ETSY", etsyJson(title, description, new String[] {"jersey", " jersey ", "royal blue white pinstripe"}, 5,
                "19.99"), "E-1", report);
        Map<String, String> clean2 = listing(csv(report.resolve("listings.csv")), "E-2");
        assertThat(clean2.get("trademarkRisk")).isEqualTo("LOW");
        assertThat(listing(csv(report.resolve("listings.csv")), "E-1").get("trademarkRisk")).isIn("MEDIUM", "HIGH");
    }

    @Test
    void anAmazonBatchGivesTheResultsTheApiGivesForTheSameListing() throws Exception {
        String title = "Zorvex " + unique() + " carry on suitcase";
        Path input = file("amazon.csv", AMAZON_HEADER + "\n"
                + "B0TEST0001," + q(title) + ",Acme,Lightweight shell,," + q("Contact us at www.example.invalid") + ","
                + q("carry on suitcase cabin bag") + ",LUGGAGE\n");
        Path mapping = file("amazon.yaml", AMAZON_MAPPING);

        BatchCheckJob.Result result = job().run(options(CLIENT_1, "AMAZON", input, mapping, "amazon-out", null, null));

        assertThat(result.rows()).isEqualTo(1);
        ObjectNode json = mapper.createObjectNode();
        json.put("title", title);
        json.put("brand", "Acme");
        json.putArray("bullet_points").add("Lightweight shell").add("").add("Contact us at www.example.invalid");
        json.put("search_terms", "carry on suitcase cabin bag");
        json.put("product_type", "LUGGAGE");
        assertSameAsApi("AMAZON", json, "B0TEST0001", dir.resolve("amazon-out"));
    }

    @Test
    void theResultsAreStoredUnderTheNamedClientAndNoOtherClientCanReadThem() throws Exception {
        Path input = file("client.csv", ETSY_HEADER + "\nC-1,Zorvex " + unique() + " mug," + q("Plain description " + unique())
                + ",mug,3,10.00,i_did,made_to_order,\n");
        Path mapping = file("client.yaml", ETSY_MAPPING);

        job().run(options(CLIENT_2, "ETSY", input, mapping, "client-out", null, null));

        Map<String, String> row = listing(csv(dir.resolve("client-out").resolve("listings.csv")), "C-1");
        String rulesId = row.get("rulesCheckId");
        String trademarkId = row.get("trademarkCheckId");
        assertThat(count("SELECT count(*) FROM listing_check l JOIN client c ON c.id = l.client_id WHERE l.id = ?::uuid AND c.name = ?",
                rulesId, CLIENT_2)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trademark_check t JOIN client c ON c.id = t.client_id WHERE t.id = ?::uuid AND c.name = ?",
                trademarkId, CLIENT_2)).isEqualTo(1);
        // client-a holds the key used here: another client's check is a 404, exactly like one that does not exist
        mvc.perform(get("/api/v1/checks/" + rulesId).header(HEADER, KEY_1)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trademark-checks/" + trademarkId).header(HEADER, KEY_1)).andExpect(status().isNotFound());
    }

    // ---- rows that cannot be read

    @Test
    void aRowWithBadDataIsReportedWithItsLineAndTheBatchGoesOn() throws Exception {
        Path input = file("bad.csv", ETSY_HEADER + "\n"
                + "B-1," + unique() + "," + q("Plain description " + unique()) + ",a,5,9.99,i_did,made_to_order,\n"   // line 2
                + "B-2,short row\n"                                                                                     // line 3
                + "B-3," + unique() + ",desc,a,five,9.99,i_did,made_to_order,\n"                                        // line 4
                + ",no id," + q("Plain description " + unique()) + ",a,1,1,i_did,made_to_order,\n"                      // line 5
                + "B-5," + unique() + "," + q("multi\nline description") + ",a,2,2.50,i_did,made_to_order,\n");        // lines 6-7
        Path mapping = file("bad.yaml", ETSY_MAPPING);

        BatchCheckJob.Result result = job().run(options(CLIENT_1, "ETSY", input, mapping, "bad-out", null, null));

        assertThat(result.rows()).isEqualTo(5);
        assertThat(result.unreadableRows()).isEqualTo(3);
        assertThat(result.failedRows()).isZero();
        List<Map<String, String>> listings = csv(dir.resolve("bad-out").resolve("listings.csv"));
        assertThat(listings).extracting(row -> row.get("line") + ":" + row.get("status"))
                .containsExactly("2:OK", "3:INPUT_ERROR", "4:INPUT_ERROR", "5:INPUT_ERROR", "6:OK");
        assertThat(listings.get(0).get("rulesCheckId")).isNotBlank();
        assertThat(listings.get(4).get("rulesCheckId")).isNotBlank();
        assertThat(listings.get(1)).containsEntry("id", "B-2").containsEntry("rulesCheckId", "");
        assertThat(listings.get(1).get("error")).contains("2 ô").contains("9 cột");
        assertThat(listings.get(2).get("error")).contains("quantity").contains("'five'");
        assertThat(listings.get(3)).containsEntry("id", "");
        assertThat(listings.get(3).get("error")).contains("cột id 'sku' để trống");
        String html = Files.readString(dir.resolve("bad-out").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<td class=\"n\">3</td><td>B-2</td>").contains("<td class=\"n\">4</td><td>B-3</td>");
    }

    @Test
    void aFileThatIsDamagedMidwayCompletesTheReportsMarksThemUnfinishedAndFails() throws Exception {
        Path input = file("damaged.csv", ETSY_HEADER + "\n"
                + "D-1," + unique() + "," + q("Plain description " + unique()) + ",a,5,9.99,i_did,made_to_order,\n"
                + "D-2,\"never closed\n");
        Path mapping = file("damaged.yaml", ETSY_MAPPING);

        assertThatThrownBy(() -> job().run(options(CLIENT_1, "ETSY", input, mapping, "damaged-out", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stopped after line 2")
                .hasMessageContaining("damaged-out");

        Path report = dir.resolve("damaged-out");
        assertThat(csv(report.resolve("listings.csv"))).extracting(row -> row.get("id")).containsExactly("D-1");
        assertThat(Files.readString(report.resolve("summary.html"), StandardCharsets.UTF_8))
                .contains("Batch bị dừng giữa chừng, báo cáo này chưa đầy đủ").contains("not well-formed CSV");
    }

    // ---- refused before anything is checked

    @Test
    void aClientThatDoesNotExistIsRefusedAndNothingIsWritten() throws Exception {
        Path input = file("c.csv", ETSY_HEADER + "\nX-1," + unique() + ",d,a,1,1,i_did,made_to_order,\n");
        Path mapping = file("c.yaml", ETSY_MAPPING);
        int before = count("SELECT count(*) FROM listing_check");

        assertThatThrownBy(() -> job().run(options("no-such-client", "ETSY", input, mapping, "none-out", null, null)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("No client named 'no-such-client'")
                .hasMessageContaining("client create");

        assertThat(dir.resolve("none-out")).doesNotExist();
        assertThat(count("SELECT count(*) FROM listing_check")).isEqualTo(before);
    }

    @Test
    void anInactiveClientIsRefusedAndNothingIsWritten() throws Exception {
        provisioning.createClient("batch-off");
        provisioning.setActive("batch-off", false);
        Path input = file("off.csv", ETSY_HEADER + "\nX-1," + unique() + ",d,a,1,1,i_did,made_to_order,\n");
        Path mapping = file("off.yaml", ETSY_MAPPING);
        int before = count("SELECT count(*) FROM listing_check");

        assertThatThrownBy(() -> job().run(options("batch-off", "ETSY", input, mapping, "off-out", null, null)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("Client 'batch-off' is inactive")
                .hasMessageContaining("client activate batch-off");

        assertThat(dir.resolve("off-out")).doesNotExist();
        assertThat(count("SELECT count(*) FROM listing_check")).isEqualTo(before);
    }

    @Test
    void aMappingThatDoesNotFitTheHeaderFailsAtOnceAndNothingIsChecked() throws Exception {
        Path input = file("m.csv", "sku,Title\nM-1," + unique() + "\n");
        Path mapping = file("m.yaml", ETSY_MAPPING);
        int before = count("SELECT count(*) FROM listing_check");

        assertThatThrownBy(() -> job().run(options(CLIENT_1, "ETSY", input, mapping, "m-out", null, null)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("m.yaml")
                .hasMessageContaining("has no column 'Description'");

        assertThat(dir.resolve("m-out")).doesNotExist();
        assertThat(count("SELECT count(*) FROM listing_check")).isEqualTo(before);
    }

    @Test
    void aMappingWithAFieldOfTheOtherMarketplaceFailsAtOnce() throws Exception {
        Path input = file("n.csv", AMAZON_HEADER + "\nB0TEST0002," + unique() + ",Acme,a,b,c,k,LUGGAGE\n");
        Path mapping = file("n.yaml", ETSY_MAPPING);

        assertThatThrownBy(() -> job().run(options(CLIENT_1, "AMAZON", input, mapping, "n-out", null, null)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("'tags' is not a field of the AMAZON input schema");
        assertThat(dir.resolve("n-out")).doesNotExist();
    }

    @Test
    void aMissingInputFileOrLabelColumnIsRefused() throws Exception {
        Path mapping = file("l.yaml", ETSY_MAPPING);
        assertThatThrownBy(() -> job().run(options(CLIENT_1, "ETSY", dir.resolve("absent.csv"), mapping, "l-out", null, null)))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("absent.csv");

        Path input = file("l.csv", ETSY_HEADER + "\nL-1," + unique() + ",d,a,1,1,i_did,made_to_order,\n");
        assertThatThrownBy(() -> job().run(options(CLIENT_1, "ETSY", input, mapping, "l-out", null, "no_such_label")))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no column 'no_such_label'");
        assertThat(dir.resolve("l-out")).doesNotExist();
    }

    @Test
    void aMarketplaceThatIsNotSupportedIsRefused() throws Exception {
        Path input = file("w.csv", ETSY_HEADER + "\n");
        Path mapping = file("w.yaml", ETSY_MAPPING);

        assertThatThrownBy(() -> job().run(options(CLIENT_1, "EBAY", input, mapping, "w-out", null, null)))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("EBAY");
    }

    // ---- limits and measurement

    @Test
    void maxRowsStopsAfterThatManyRowsAndSaysSo() throws Exception {
        StringBuilder rows = new StringBuilder(ETSY_HEADER + "\n");
        for (int i = 1; i <= 4; i++) {
            rows.append("R-").append(i).append(',').append(unique()).append(",d,a,1,1,i_did,made_to_order,\n");
        }
        Path input = file("max.csv", rows.toString());
        Path mapping = file("max.yaml", ETSY_MAPPING);

        BatchCheckJob.Result result = job().run(options(CLIENT_1, "ETSY", input, mapping, "max-out", 2, null));

        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.stoppedAtMaxRows()).isTrue();
        assertThat(csv(dir.resolve("max-out").resolve("listings.csv"))).extracting(row -> row.get("id")).containsExactly("R-1", "R-2");
        assertThat(Files.readString(dir.resolve("max-out").resolve("summary.html"), StandardCharsets.UTF_8))
                .contains("Chỉ kiểm tra <b>2</b> dòng dữ liệu đầu tiên");

        BatchCheckJob.Result all = job().run(options(CLIENT_1, "ETSY", input, mapping, "max-all", 4, null));
        assertThat(all.rows()).isEqualTo(4);
        assertThat(all.stoppedAtMaxRows()).as("exactly as many rows as asked for is not a stop").isFalse();
    }

    @Test
    void theMeasurementMatchesAnAnswerWorkedOutByHand() throws Exception {
        String marked = "Zorvex %s jersey";
        Path input = file("label.csv", ETSY_HEADER + "\n"
                + "T-1," + q(marked.formatted(unique())) + ",d,a,1,1,i_did,made_to_order,true\n"      // true, flagged
                + "T-2," + q(marked.formatted(unique())) + ",d,a,1,1,i_did,made_to_order,YES\n"       // true, flagged
                + "T-3," + unique() + ",d,a,1,1,i_did,made_to_order,1\n"                              // true, missed
                + "F-1," + unique() + ",d,a,1,1,i_did,made_to_order,false\n"                          // false, clear
                + "F-2," + unique() + ",d,a,1,1,i_did,made_to_order,0\n"                              // false, clear
                + "F-3," + q(marked.formatted(unique())) + ",d,a,1,1,i_did,made_to_order,no\n"        // false, false alarm
                + "U-1," + unique() + ",d,a,1,1,i_did,made_to_order,\n"                               // no label
                + "E-1," + unique() + ",d,a,1,1,i_did,made_to_order,maybe\n");                        // unreadable label
        Path mapping = file("label.yaml", ETSY_MAPPING);

        BatchCheckJob.Result result = job().run(options(CLIENT_1, "ETSY", input, mapping, "label-out", null, "Takedown"));

        assertThat(result.rows()).isEqualTo(8);
        assertThat(result.unreadableRows()).isEqualTo(1);
        List<Map<String, String>> listings = csv(dir.resolve("label-out").resolve("listings.csv"));
        assertThat(listing(listings, "T-1").get("trademarkRisk")).isIn("MEDIUM", "HIGH");
        assertThat(listing(listings, "T-3").get("trademarkRisk")).isEqualTo("LOW");
        assertThat(listing(listings, "T-1")).containsEntry("label", "true");
        assertThat(listing(listings, "F-3")).containsEntry("label", "false");
        assertThat(listing(listings, "U-1")).containsEntry("label", "");
        assertThat(listing(listings, "E-1").get("error")).contains("nhãn 'Takedown'").contains("'maybe'");
        String html = Files.readString(dir.resolve("label-out").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("Đo lường theo nhãn <code>Takedown</code>")
                .contains("<b>Tỷ lệ phát hiện</b> (nhãn true bị gắn cờ): 2/3 (66.7%)")
                .contains("<b>Tỷ lệ báo nhầm</b> (nhãn false bị gắn cờ): 1/3 (33.3%)")
                .contains("1 listing để trống nhãn, 1 dòng lỗi dữ liệu")
                .contains("(3 listing nhãn true, 3 listing nhãn false)")
                .contains("không phải độ chính xác tổng quát");
    }

    // ---- stability

    @Test
    void aSecondRunWritesTheSameFilesExceptTheRunTime() throws Exception {
        String title = "Zorvex " + unique() + " tote bag";
        Path input = file("again.csv", ETSY_HEADER + "\n"
                + "A-1," + q(title) + ",d,\"tote,bag\",2,5.00,i_did,made_to_order,true\n"
                + "A-2," + unique() + ",d,tote,2,5.00,i_did,made_to_order,false\n"
                + "A-3,short\n");
        Path mapping = file("again.yaml", ETSY_MAPPING);
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        job(console, RUN_1).run(options(CLIENT_1, "ETSY", input, mapping, "run-1", null, "Takedown"));
        job().run(options(CLIENT_1, "ETSY", input, mapping, "run-2", null, "Takedown"));
        BatchCheckJob secondRun = job(console, RUN_2);
        secondRun.run(options(CLIENT_1, "ETSY", input, mapping, "run-3", null, "Takedown"));

        for (String other : List.of("run-2", "run-3")) {
            for (String name : List.of("findings.csv", "listings.csv")) {
                assertThat(Files.readAllBytes(dir.resolve(other).resolve(name))).as(other + "/" + name)
                        .isEqualTo(Files.readAllBytes(dir.resolve("run-1").resolve(name)));
            }
        }
        String first = Files.readString(dir.resolve("run-1").resolve("summary.html"), StandardCharsets.UTF_8);
        String third = Files.readString(dir.resolve("run-3").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(third).contains("2026-11-02 17:45:10 UTC").isNotEqualTo(first);
        assertThat(third.replaceAll("<time datetime=\"[^\"]*\">[^<]*</time>", "<time/>"))
                .isEqualTo(first.replaceAll("<time datetime=\"[^\"]*\">[^<]*</time>", "<time/>"));
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Batch check finished: 3 rows, 1 unreadable, 0 with a failed check");
    }

    @Test
    void theSummaryOfARealRunHasNothingThatPointsOutsideTheFile() throws Exception {
        Path input = file("html.csv", ETSY_HEADER + "\nH-1,Zorvex " + unique() + " cap,d,a,1,1,i_did,made_to_order,\n");
        Path mapping = file("html.yaml", ETSY_MAPPING);

        job().run(options(CLIENT_1, "ETSY", input, mapping, "html-out", null, null));

        String html = Files.readString(dir.resolve("html-out").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains(rulesets.ruleset().disclaimer()).contains(TrademarkReadyTestConfig.catalogVersion())
                .contains(rulesets.ruleset().version());
        assertThat(html).doesNotContain("<script").doesNotContain("<link").doesNotContain("<img").doesNotContain("src=")
                .doesNotContain("href=").doesNotContain("url(").doesNotContain("@import").doesNotContain("http:")
                .doesNotContain("https:");
    }
}
