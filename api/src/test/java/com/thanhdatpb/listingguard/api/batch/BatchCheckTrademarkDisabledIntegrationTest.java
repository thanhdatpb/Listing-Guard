package com.thanhdatpb.listingguard.api.batch;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_HEADER;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_MAPPING;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.csv;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * No trademark catalog is configured (the default): the batch still runs the rules, stores no trademark result, and the
 * report says that the trademark check was not configured instead of showing a clean result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BatchCheckTrademarkDisabledIntegrationTest {

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

    @TempDir
    Path dir;

    @Test
    void theRulesStillRunAndTheReportSaysTheTrademarkCheckIsNotConfigured() throws Exception {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.DISABLED);
        String title = "Zorvex " + unique() + " mug";
        Path input = Files.writeString(dir.resolve("in.csv"), ETSY_HEADER + "\n"
                + "N-1," + title + ",d,\"mug,mug\",5,9.99,i_did,made_to_order,true\n"
                + "N-2," + unique() + ",d,mug,5,9.99,i_did,made_to_order,false\n", StandardCharsets.UTF_8);
        Path mapping = Files.writeString(dir.resolve("map.yaml"), ETSY_MAPPING, StandardCharsets.UTF_8);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        int trademarkRowsBefore = jdbc.queryForObject("SELECT count(*) FROM trademark_check", Integer.class);
        BatchCheckJob job = new BatchCheckJob(clients, normalizers, checks, trademarkChecks, runtime, rulesets,
                new PrintStream(console, true, StandardCharsets.UTF_8), Clock.fixed(Instant.parse("2026-10-30T09:15:30Z"), ZoneOffset.UTC));

        BatchCheckJob.Result result = job.run(new BatchOptions(input, mapping, CLIENT_1, "ETSY", dir.resolve("out"), null, "Takedown"));

        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.failedRows()).isZero();
        List<Map<String, String>> listings = csv(dir.resolve("out").resolve("listings.csv"));
        for (Map<String, String> row : listings) {
            assertThat(row).containsEntry("status", "OK").containsEntry("trademarkRisk", "NOT_CONFIGURED")
                    .containsEntry("completeness", "").containsEntry("trademarkCheckId", "");
            assertThat(row.get("rulesCheckId")).isNotBlank();
        }
        assertThat(csv(dir.resolve("out").resolve("findings.csv"))).isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("check", "rules"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_check", Integer.class)).isEqualTo(trademarkRowsBefore);

        // the rule results are the API's: the listing the batch stored answers the API from the cache
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        listing.put("description", "d");
        listing.putArray("tags").add("mug").add("mug");
        listing.put("quantity", 5);
        listing.put("price", "9.99");
        listing.put("who_made", "i_did");
        listing.put("when_made", "made_to_order");
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.set("listing", listing);
        JsonNode api = mapper.readTree(mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(api.get("cached").asBoolean()).isTrue();
        assertThat(api.get("checkId").asText()).isEqualTo(listings.get(0).get("rulesCheckId"));

        String html = Files.readString(dir.resolve("out").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<b>Trademark chưa cấu hình</b>").contains("no catalog version is configured")
                .contains("Phần rule vẫn được chạy")
                .contains("<td>Trademark chưa cấu hình</td><td class=\"n\">2</td>")
                .contains("Không đo được: trademark chưa cấu hình")
                .contains(rulesets.ruleset().disclaimer());
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Trademark checks were not run (DISABLED");
    }
}
