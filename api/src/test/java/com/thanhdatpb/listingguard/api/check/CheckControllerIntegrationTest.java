package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CheckControllerIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ListingCheckRepository checks;

    @Autowired
    FindingRepository findings;

    @Autowired
    ActiveRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    /** A listing that satisfies every Etsy rule; the title makes it unique so tests never share a cached result. */
    private String validBody() {
        return """
                {
                  "marketplace": "ETSY",
                  "listing": {
                    "title": "Handmade Mug %s",
                    "description": "A ceramic mug.",
                    "tags": ["mug", "ceramic"],
                    "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                    "taxonomy_path": ["Home & Living", "Mugs"],
                    "materials": ["clay"],
                    "images": ["https://example.com/1.jpg"],
                    "quantity": 5,
                    "who_made": "i_did",
                    "when_made": "made_to_order"
                  }
                }
                """.formatted(UUID.randomUUID());
    }

    private JsonNode postOk(String body) throws Exception {
        String response = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    @Test
    void validListingIsStoredAndReturnedWithTheStableShape() throws Exception {
        String response = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.marketplace").value("ETSY"))
                .andExpect(jsonPath("$.rulesetVersion").value("etsy-2026-11"))
                .andExpect(jsonPath("$.analysisVersion").value("etsy-2026-11"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.summary.errors").value(0))
                .andExpect(jsonPath("$.summary.warnings").value(0))
                .andExpect(jsonPath("$.summary.infos").value(0))
                .andExpect(jsonPath("$.findings").isEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(response);
        UUID checkId = UUID.fromString(json.get("checkId").asText());
        assertThat(json.get("contentHash").asText()).matches("[0-9a-f]{64}");
        ListingCheck saved = checks.findById(checkId).orElseThrow();
        assertThat(saved.getMarketplaceCode()).isEqualTo("ETSY");
        assertThat(saved.getStatus()).isEqualTo(CheckStatus.COMPLETED);
        assertThat(saved.getRulesetVersionId()).isEqualTo(rulesets.get("ETSY").rulesetVersionId());
        assertThat(findings.findByCheckId(checkId)).isEmpty();
    }

    @Test
    void violatingListingReturnsFindingsWithSourceValueAndSummary() throws Exception {
        JsonNode json = postOk(violatingBody());

        assertThat(json.get("summary").get("errors").asInt()).isPositive();
        assertThat(json.get("summary").get("warnings").asInt()).isPositive();
        List<String> ruleIds = new ArrayList<>();
        json.get("findings").forEach(finding -> {
            ruleIds.add(finding.get("ruleId").asText());
            assertThat(finding.get("source").asText()).isEqualTo("RULE");
            assertThat(finding.get("message").asText()).isNotBlank();
            assertThat(finding.get("suggestion").asText()).isNotBlank();
            assertThat(finding.has("value")).isTrue();
        });
        assertThat(ruleIds).containsExactlyInAnyOrder("required_fields", "title_length", "tag_count", "tag_length",
                "tag_characters", "duplicate_tags", "banned_words");

        JsonNode badTag = findingOf(json, "tag_characters");
        assertThat(badTag.get("value").asText()).isEqualTo("bad_tag");
        assertThat(badTag.get("field").asText()).startsWith("tags[");
        assertThat(findingOf(json, "required_fields").get("field").asText()).isEqualTo("quantity");
        assertThat(findingOf(json, "required_fields").get("value").isNull()).isTrue();
        assertThat(findingOf(json, "title_length").get("severity").asText()).isEqualTo("ERROR");
        assertThat(findingOf(json, "duplicate_tags").get("severity").asText()).isEqualTo("WARNING");

        long errors = json.get("findings").findValues("severity").stream().filter(s -> s.asText().equals("ERROR")).count();
        assertThat(json.get("summary").get("errors").asLong()).isEqualTo(errors);

        UUID checkId = UUID.fromString(json.get("checkId").asText());
        long rulesetVersionId = rulesets.get("ETSY").rulesetVersionId();
        List<FindingEntity> saved = findings.findByCheckId(checkId);
        assertThat(saved).hasSize(ruleIds.size());
        assertThat(saved).allSatisfy(entity -> assertThat(entity.getRulesetVersionId()).isEqualTo(rulesetVersionId));
        assertThat(saved).filteredOn(entity -> entity.getRuleId().equals("title_length"))
                .extracting(FindingEntity::getSeverity).containsExactly(Severity.ERROR);
    }

    @Test
    void identicalListingGetsTheStoredResultBackAndIsNotStoredTwice() throws Exception {
        String body = validBody();

        JsonNode first = postOk(body);
        JsonNode second = postOk(body);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId").asText()).isEqualTo(first.get("checkId").asText());
        assertThat(second.get("createdAt").asText()).isEqualTo(first.get("createdAt").asText());
        assertThat(second.get("contentHash").asText()).isEqualTo(first.get("contentHash").asText());
        assertThat(countChecks(first.get("contentHash").asText())).isEqualTo(1);
    }

    @Test
    void aDifferentAnalysisVersionIsNotAnsweredFromTheStoredResult() throws Exception {
        String body = validBody();
        JsonNode first = postOk(body);
        Long otherVersion = jdbc.queryForObject("""
                INSERT INTO ruleset_version (marketplace_code, version, content_hash, checked_at, source)
                VALUES ('ETSY', ?, 'f', current_date, 'test') RETURNING id
                """, Long.class, "etsy-test-" + UUID.randomUUID());
        jdbc.update("UPDATE listing_check SET ruleset_version_id = ? WHERE id = ?", otherVersion, UUID.fromString(first.get("checkId").asText()));

        JsonNode second = postOk(body);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId").asText()).isNotEqualTo(first.get("checkId").asText());
        assertThat(countChecks(first.get("contentHash").asText())).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalRequestsAreStoredOnceAndAllAnswerFromThatOneCheck() throws Exception {
        String body = validBody();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return postOk(body);
                }));
            }
            ready.await();
            go.countDown();

            Set<String> ids = new HashSet<>();
            int fresh = 0;
            String hash = null;
            for (Future<JsonNode> future : futures) {
                JsonNode result = future.get();
                ids.add(result.get("checkId").asText());
                fresh += result.get("cached").asBoolean() ? 0 : 1;
                hash = result.get("contentHash").asText();
            }

            assertThat(ids).as("every request answers with the same stored check").hasSize(1);
            assertThat(fresh).as("exactly one request did the work").isEqualTo(1);
            assertThat(countChecks(hash)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aStoredCheckCanBeReadBackById() throws Exception {
        JsonNode created = postOk(violatingBody());

        String response = mvc.perform(get(CHECKS + "/" + created.get("checkId").asText()).header(HEADER, KEY_1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode fetched = mapper.readTree(response);
        assertThat(fetched.get("cached").asBoolean()).isFalse();
        ObjectNode expected = created.deepCopy();
        expected.put("cached", false);
        assertThat(fetched).isEqualTo(expected);
    }

    @Test
    void anUnknownCheckIdIs404WithAProblemCode() throws Exception {
        mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("check-not-found"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:check-not-found"));
        mvc.perform(get(CHECKS + "/not-a-uuid").header(HEADER, KEY_1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void unknownMarketplaceIs400AndAPlannedOneIs422() throws Exception {
        // WALMART is the planned marketplace configured for the tests (TestcontainersConfig); none is planned in production
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"NOPE\",\"listing\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-marketplace"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Supported: AMAZON, ETSY")));
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"walmart\",\"listing\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("marketplace-not-yet-supported"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("WALMART")));
    }

    @Test
    void amazonIsNoLongerAPlannedMarketplace() throws Exception {
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"amazon\",\"listing\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplace").value("AMAZON"));
    }

    @Test
    void invalidRequestsAre400WithFieldErrors() throws Exception {
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{\"listing\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("marketplace"));
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{\"marketplace\":\"ETSY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("listing"));
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{\"marketplace\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void theOldUnversionedRouteIsGone() throws Exception {
        mvc.perform(post("/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    private int countChecks(String contentHash) {
        return jdbc.queryForObject("SELECT count(*) FROM listing_check WHERE content_hash = ?", Integer.class, contentHash);
    }

    private static JsonNode findingOf(JsonNode result, String ruleId) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding for rule " + ruleId);
    }

    /** Breaks every Etsy rule once (except price/quantity ones). Limits come from the active ruleset. */
    private String violatingBody() throws Exception {
        RulesetConfig config = rulesets.get("ETSY").config();
        int maxTags = config.rule("tag_count").intParam("max_count");
        int maxTagLength = config.rule("tag_length").intParam("max_length");
        int maxTitle = config.rule("title_length").intParam("max_length");
        String bannedWord = config.rule("banned_words").stringListParam("words").get(0);

        ObjectNode listing = (ObjectNode) mapper.readTree(validBody()).get("listing");
        listing.put("title", "t".repeat(maxTitle + 1));
        listing.put("description", "This item is a " + bannedWord + " " + UUID.randomUUID() + ".");
        listing.remove("quantity");
        ArrayNode tags = listing.putArray("tags");
        for (int i = 0; i < maxTags; i++) {
            tags.add("tag" + i);
        }
        tags.add("x".repeat(maxTagLength + 1));
        tags.add("bad_tag");
        tags.add("TAG0");

        ObjectNode body = mapper.createObjectNode().put("marketplace", "ETSY");
        body.set("listing", listing);
        return body.toString();
    }
}
