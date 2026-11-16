package com.thanhdatpb.listingguard.api.feedback;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/feedback} and {@code GET /api/v1/feedback/stats} (ADR-034) against a real database: only a finding of the
 * caller's own results can be answered, a second answer changes the first, the note limit, expired results, what is stored (no
 * listing text), and the statistics on synthetic data. Every name is invented.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class FeedbackApiIntegrationTest {

    /** The real time: the check service stamps results with the system clock, so the period must hold today. */
    private Instant NOW;
    private String today;
    private String weekAgo;

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
    MutableClock clock;

    @BeforeEach
    void now() {
        NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        clock.set(NOW);
        java.time.LocalDate day = java.time.LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        today = day.toString();
        weekAgo = day.minusDays(6).toString();
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    /** A stored listing-rules result of the client with a few findings (only a title: required fields are missing). */
    private UUID check(TestClient client, String title) throws Exception {
        String answer = mvc.perform(post("/api/v1/checks").header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(title)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(answer);
        assertThat(json.get("findings").size()).isGreaterThanOrEqualTo(2);
        return UUID.fromString(json.get("checkId").asText());
    }

    private ResultActions feedback(TestClient client, String pack, UUID checkId, int index, String verdict, String note)
            throws Exception {
        String json = mapper.writeValueAsString(new FeedbackRequest(pack, checkId, index, verdict, note));
        return mvc.perform(post("/api/v1/feedback").header(HEADER, client.key()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int rows(TestClient client) {
        return jdbc.queryForObject("SELECT count(*) FROM finding_verdict WHERE client_id = ?", Integer.class, client.id());
    }

    @Test
    void aVerdictIsStoredWithWhatItCopiesOfTheFindingAndNoListingText() throws Exception {
        TestClient c = client();
        String title = "Synthetic " + unique();
        UUID checkId = check(c, title);
        String ruleOfFirst = mapper.readTree(mvc.perform(get("/api/v1/checks/" + checkId).header(HEADER, c.key()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).at("/findings/0/ruleId").asText();

        feedback(c, "listing-rules", checkId, 0, "FALSE_POSITIVE", "  the word belongs to the product  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.ruleId").value(ruleOfFirst))
                .andExpect(jsonPath("$.source").value("RULE"))
                .andExpect(jsonPath("$.marketplace").value("ETSY"))
                .andExpect(jsonPath("$.analysisVersion").isNotEmpty())
                .andExpect(jsonPath("$.verdict").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.note").value("the word belongs to the product"));

        assertThat(rows(c)).isEqualTo(1);
        String stored = jdbc.queryForMap("SELECT * FROM finding_verdict WHERE client_id = ?", c.id()).toString();
        assertThat(stored).doesNotContain(title).contains(ruleOfFirst);
        // no plan unit was charged: feedback stores no result
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding_verdict f JOIN api_key k ON k.id = f.api_key_id "
                + "WHERE f.client_id = ? AND k.client_id = ?", Integer.class, c.id(), c.id())).isEqualTo(1);
    }

    @Test
    void aSecondVerdictOfTheSameKeyChangesTheFirstAndKeepsOneRow() throws Exception {
        TestClient c = client();
        UUID checkId = check(c, "Synthetic " + unique());

        feedback(c, "listing-rules", checkId, 1, "CORRECT", "first").andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
        clock.set(NOW.plusSeconds(60));
        String second = feedback(c, "listing-rules", checkId, 1, "NOT_USEFUL", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.verdict").value("NOT_USEFUL"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(rows(c)).isEqualTo(1);
        JsonNode json = mapper.readTree(second);
        assertThat(json.get("note").isNull()).isTrue();
        assertThat(Instant.parse(json.get("updatedAt").asText())).isAfter(Instant.parse(json.get("createdAt").asText()));
        assertThat(jdbc.queryForObject("SELECT verdict FROM finding_verdict WHERE client_id = ?", String.class, c.id()))
                .isEqualTo("NOT_USEFUL");
    }

    @Test
    void anotherClientsFindingAnUnknownResultAndAnIndexOutsideAreOneNotFound() throws Exception {
        TestClient owner = client();
        TestClient other = client();
        UUID checkId = check(owner, "Synthetic " + unique());

        feedback(other, "listing-rules", checkId, 0, "FALSE_POSITIVE", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("finding-not-found"));
        feedback(owner, "listing-rules", UUID.randomUUID(), 0, "CORRECT", null).andExpect(status().isNotFound());
        feedback(owner, "listing-rules", checkId, 999, "CORRECT", null).andExpect(status().isNotFound());
        feedback(owner, "trademark-risk", checkId, 0, "CORRECT", null).andExpect(status().isNotFound());

        assertThat(rows(owner)).isZero();
        assertThat(rows(other)).isZero();
    }

    @Test
    void aWrongPackVerdictOrNoteIsRefusedAndNothingIsStored() throws Exception {
        TestClient c = client();
        UUID checkId = check(c, "Synthetic " + unique());

        feedback(c, "personalization-screening", checkId, 0, "CORRECT", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-feedback"));
        feedback(c, "listing-rules", checkId, 0, "MAYBE", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-feedback"));
        String longNote = "n".repeat(FindingFeedbackService.MAX_NOTE_CHARS + 1);
        String answer = feedback(c, "listing-rules", checkId, 0, "CORRECT", longNote).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-feedback"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(answer).doesNotContain(longNote);
        mvc.perform(post("/api/v1/feedback").header(HEADER, c.key()).contentType(MediaType.APPLICATION_JSON).content("{\"pack\":\"listing-rules\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post("/api/v1/feedback").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());

        assertThat(rows(c)).isZero();
    }

    @Test
    void aTrademarkFindingIsAnsweredWhileKeptAndNotAfterItsRetention() throws Exception {
        TestClient c = client();
        UUID kept = trademark(c, NOW.minusSeconds(3600), NOW.plusSeconds(86400L * 3650), "TM_MARK_KEPT");
        UUID expired = trademark(c, Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2000-01-02T00:00:00Z"), null);

        feedback(c, "trademark-risk", kept, 0, "CORRECT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CATALOG")).andExpect(jsonPath("$.level").value("HIGH"));
        feedback(c, "trademark-risk", expired, 0, "CORRECT", null).andExpect(status().isNotFound());
    }

    @Test
    void theStatisticsCountVerdictsPerRuleAndReviewerOnSyntheticData() throws Exception {
        TestClient c = client();
        UUID first = check(c, "Synthetic " + unique());
        UUID second = check(c, "Synthetic " + unique());
        feedback(c, "listing-rules", first, 0, "FALSE_POSITIVE", "secret-note-4471").andExpect(status().isOk());
        feedback(c, "listing-rules", second, 0, "FALSE_POSITIVE", null).andExpect(status().isOk());
        feedback(c, "listing-rules", second, 1, "CORRECT", null).andExpect(status().isOk());
        UUID tm = trademark(c, NOW.minusSeconds(3600), NOW.plusSeconds(86400L * 3650), null);
        feedback(c, "trademark-risk", tm, 0, "NOT_USEFUL", null).andExpect(status().isOk());
        String rule = jdbc.queryForObject("SELECT rule_id FROM finding_verdict WHERE client_id = ? AND check_id = ?", String.class,
                c.id(), first);

        String body = mvc.perform(get("/api/v1/feedback/stats").header(HEADER, c.key()).param("from", weekAgo)
                        .param("to", today))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(body);

        assertThat(json.at("/totals/verdicts").asLong()).isEqualTo(4);
        assertThat(json.at("/totals/byVerdict/FALSE_POSITIVE").asLong()).isEqualTo(2);
        assertThat(json.at("/totals/byVerdict/CORRECT").asLong()).isEqualTo(1);
        assertThat(json.at("/totals/byVerdict/NOT_USEFUL").asLong()).isEqualTo(1);
        assertThat(json.at("/totals/falsePositiveRate").asDouble()).isEqualTo(0.5);
        JsonNode top = json.at("/byRule/0");
        assertThat(top.get("ruleId").asText()).isEqualTo(rule);
        assertThat(top.get("falsePositive").asLong()).isEqualTo(2);
        assertThat(top.get("findings").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode trademarkRule = null;
        for (JsonNode item : json.get("byRule")) {
            if (item.get("pack").asText().equals("trademark-risk")) {
                trademarkRule = item;
            }
        }
        assertThat(trademarkRule).isNotNull();
        assertThat(trademarkRule.get("source").asText()).isEqualTo("RULE");
        assertThat(trademarkRule.get("falsePositiveRate").asDouble()).isZero();
        assertThat(json.get("byReviewer")).hasSize(1);
        assertThat(json.at("/byReviewer/0/kind").asText()).isEqualTo("API_KEY");
        assertThat(json.at("/byReviewer/0/verdicts").asLong()).isEqualTo(4);
        assertThat(json.at("/byReviewer/0/display").isMissingNode()).isTrue();
        assertThat(body).doesNotContain("secret-note-4471");

        // another client sees none of it, and a refused period is the report's problem
        String other = mvc.perform(get("/api/v1/feedback/stats").header(HEADER, client().key()).param("from", weekAgo)
                .param("to", today)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(other).at("/totals/verdicts").asLong()).isZero();
        mvc.perform(get("/api/v1/feedback/stats").header(HEADER, c.key()).param("from", today).param("to", weekAgo))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-report-period"));
    }

    @Test
    void theHealthReportCarriesTheRulesMostOftenMarkedFalsePositive() throws Exception {
        TestClient c = client();
        UUID checkId = check(c, "Synthetic " + unique());
        feedback(c, "listing-rules", checkId, 0, "FALSE_POSITIVE", "hidden-note-9912").andExpect(status().isOk());

        String body = mvc.perform(get("/api/v1/reports/summary").header(HEADER, c.key()).param("from", today)
                .param("to", today)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode feedback = mapper.readTree(body).get("feedback");

        assertThat(feedback.get("verdicts").asLong()).isEqualTo(1);
        assertThat(feedback.at("/topFalsePositiveRules/items/0/falsePositives").asLong()).isEqualTo(1);
        assertThat(feedback.at("/topFalsePositiveRules/items/0/falsePositiveRate").asDouble()).isEqualTo(1.0);
        assertThat(body).doesNotContain("hidden-note-9912");
    }

    /** A trademark result with one HIGH finding, written straight into the tables (synthetic mark, or none for a context rule). */
    private UUID trademark(TestClient c, Instant created, Instant expires, String mark) {
        UUID id = UUID.randomUUID();
        String hex = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        jdbc.update("""
                INSERT INTO trademark_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    catalog_version_id, analysis_version, completeness, risk_level, disclaimer, created_at, expires_at)
                VALUES (?, ?, 'ETSY', 'trademark-risk', ?,
                    (SELECT id FROM ruleset_version WHERE pack_id = 'trademark-risk' AND marketplace_code = 'ETSY' ORDER BY id DESC LIMIT 1),
                    (SELECT id FROM trademark_catalog_version ORDER BY id LIMIT 1), ?, 'COMPLETE', 'HIGH', 'd', ?, ?)""",
                id, c.id(), hex, hex, at(created), at(expires));
        jdbc.update("""
                INSERT INTO trademark_check_finding (check_id, position, rule_id, risk, field, span_start, span_end, snippet,
                    matched_mark, match_type, inside_color_phrase, message, suggestion)
                VALUES (?, 0, ?, 'HIGH', 'title', 0, 1, 'snippet', ?, ?, FALSE, 'message', 'suggestion')""",
                id, mark == null ? "geo_sport_combination" : "catalog_exact", mark, mark == null ? "CONTEXT" : "EXACT");
        return id;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
