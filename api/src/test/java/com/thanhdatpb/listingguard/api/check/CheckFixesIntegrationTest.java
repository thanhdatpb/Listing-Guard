package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"listing-guard.fixes.enabled=true", "listing-guard.plans.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CheckFixesIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @SpyBean
    RuleEngine ruleEngine;

    @Test
    void returnsAndStoresVerifiedFixesAndReadinessForCleanedTagIndexes() throws Exception {
        JsonNode result = postOk(bodyWithBlankAndRepeatedTags());
        JsonNode repeated = findingOf(result, "duplicate_tags");
        JsonNode shortened = findingOf(result, "tag_length");

        assertThat(result.get("fixesComputed").asBoolean()).isTrue();
        assertThat(result.get("readiness").get("version").asText()).isEqualTo("readiness-2026-11");
        assertThat(result.get("readiness").get("status").asText()).isEqualTo("SCORED");
        assertThat(result.get("readiness").get("ready").asBoolean()).isFalse();
        assertThat(result.get("readiness").get("score").asInt()).isLessThanOrEqualTo(59);

        assertThat(repeated.get("field").asText()).isEqualTo("tags[2]");
        assertThat(repeated.get("value").asText()).isEqualTo("a very very long tag name");
        assertThat(repeated.get("fix").get("action").asText()).isEqualTo("REMOVE");
        assertThat(repeated.get("fix").get("proposedValue").isNull()).isTrue();
        assertThat(repeated.get("fix").get("description").asText()).contains("a very very long tag name");

        assertThat(shortened.get("field").asText()).isEqualTo("tags[1]");
        assertThat(shortened.get("value").asText()).isEqualTo("a very very long tag name");
        assertThat(shortened.get("fix").get("action").asText()).isEqualTo("REPLACE");
        assertThat(shortened.get("fix").get("proposedValue").asText()).isNotBlank();
        assertThat(shortened.get("fix").get("description").asText()).contains("a very very long tag name");

        UUID checkId = UUID.fromString(result.get("checkId").asText());
        assertThat(jdbc.queryForObject("SELECT fixes_computed FROM listing_check WHERE id = ?", Boolean.class, checkId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE check_id = ? AND fix_action IS NOT NULL",
                Integer.class, checkId)).isEqualTo(3);
    }

    @Test
    void enrichesLegacyCacheHitOnceForConcurrentPostsWithoutChargingQuota() throws Exception {
        String body = bodyWithBlankAndRepeatedTags();
        JsonNode initial = postOk(body);
        UUID checkId = UUID.fromString(initial.get("checkId").asText());
        UUID clientId = jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, ApiTestKeys.CLIENT_1);
        LocalDate periodStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        int usedBefore = jdbc.queryForObject(
                "SELECT COALESCE((SELECT used FROM client_usage WHERE client_id = ? AND period_start = ?), 0)",
                Integer.class, clientId, periodStart);

        jdbc.update("""
                UPDATE listing_check
                SET fixes_computed = FALSE, readiness_score = NULL, readiness_ready = NULL,
                    readiness_status = NULL, readiness_version = NULL
                WHERE id = ?
                """, checkId);
        jdbc.update("""
                UPDATE finding
                SET fix_action = NULL, fix_description = NULL, fix_proposed_value = NULL
                WHERE check_id = ?
                """, checkId);

        int threads = 2;
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
            for (Future<JsonNode> future : futures) {
                JsonNode cached = future.get();
                ids.add(cached.get("checkId").asText());
                assertThat(cached.get("cached").asBoolean()).isTrue();
                assertThat(cached.get("fixesComputed").asBoolean()).isTrue();
                assertThat(cached.get("readiness").isNull()).isFalse();
            }
            assertThat(ids).containsExactly(checkId.toString());
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT fixes_computed FROM listing_check WHERE id = ?", Boolean.class, checkId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE check_id = ? AND fix_action IS NOT NULL",
                Integer.class, checkId)).isEqualTo(3);
        int usedAfter = jdbc.queryForObject(
                "SELECT COALESCE((SELECT used FROM client_usage WHERE client_id = ? AND period_start = ?), 0)",
                Integer.class, clientId, periodStart);
        assertThat(usedAfter).isEqualTo(usedBefore);

        JsonNode stored = mapper.readTree(mvc.perform(get(CHECKS + "/" + checkId).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stored.get("fixesComputed").asBoolean()).isTrue();
    }

    @Test
    void legacyGetReturnsExplicitMissingDetailsWithoutOriginalListing() throws Exception {
        JsonNode created = postOk(bodyWithBlankAndRepeatedTags());
        UUID checkId = UUID.fromString(created.get("checkId").asText());
        jdbc.update("""
                UPDATE listing_check
                SET fixes_computed = FALSE, readiness_score = NULL, readiness_ready = NULL,
                    readiness_status = NULL, readiness_version = NULL
                WHERE id = ?
                """, checkId);
        jdbc.update("""
                UPDATE finding
                SET fix_action = NULL, fix_description = NULL, fix_proposed_value = NULL
                WHERE check_id = ?
                """, checkId);

        JsonNode fetched = mapper.readTree(mvc.perform(get(CHECKS + "/" + checkId).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(fetched.get("fixesComputed").asBoolean()).isFalse();
        assertThat(fetched.has("readiness")).isTrue();
        assertThat(fetched.get("readiness").isNull()).isTrue();
        for (JsonNode finding : fetched.get("findings")) {
            assertThat(finding.has("fix")).isTrue();
            assertThat(finding.get("fix").isNull()).isTrue();
        }
    }

    @Test
    void incompleteRuleRunReturnsNullScoreAndNotReady() throws Exception {
        doReturn(new RuleRun("listing-rules", List.of(), List.of("synthetic_rule")))
                .when(ruleEngine).runDetailed(any(Listing.class), any(RulesetConfig.class));

        JsonNode result = postOk(bodyWithBlankAndRepeatedTags());
        JsonNode readiness = result.get("readiness");

        assertThat(readiness.get("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(readiness.has("score")).isTrue();
        assertThat(readiness.get("score").isNull()).isTrue();
        assertThat(readiness.get("ready").asBoolean()).isFalse();
    }

    private JsonNode postOk(String body) throws Exception {
        String response = mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private static JsonNode findingOf(JsonNode result, String ruleId) {
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding for rule " + ruleId);
    }

    private static String bodyWithBlankAndRepeatedTags() {
        return """
                {
                  "marketplace": "ETSY",
                  "listing": {
                    "title": "Handmade Ceramic Mug %s",
                    "description": "A ceramic mug.",
                    "tags": ["mug", "", "a very very long tag name", "a very very long tag name"],
                    "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                    "taxonomy_path": ["Home & Living", "Mugs"],
                    "materials": ["clay"],
                    "images": ["https://example.com/1.jpg"],
                    "quantity": 5,
                    "who_made": "i_did",
                    "when_made": "made_to_order"
                  }
                }
                """.formatted(UUID.randomUUID().toString().replace("-", ""));
    }
}
