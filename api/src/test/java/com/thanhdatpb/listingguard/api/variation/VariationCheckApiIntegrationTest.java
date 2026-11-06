package com.thanhdatpb.listingguard.api.variation;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1_SECOND;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.amazonFamily;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.etsyFamily;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.variation.CanonicalInput;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/variation-checks} and {@code GET /api/v1/variation-checks/{id}} (ADR-015, ADR-010): the response, the
 * cache and client isolation at the HTTP level, retention, that no submitted text is stored, and that identical requests at
 * the same moment store one result. Plans are off here (the default); the quota has its own test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class VariationCheckApiIntegrationTest {

    private static final String VARIATION = "/api/v1/variation-checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VariationRulesets rulesets;

    @Autowired
    VariationNormalizer normalizer;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    private ResultActions perform(String key, String json) throws Exception {
        return mvc.perform(post(VARIATION).header(HEADER, key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode ok(String key, JsonNode body) throws Exception {
        String response = perform(key, MAPPER.writeValueAsString(body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response);
    }

    private JsonNode read(String key, String id) throws Exception {
        String response = mvc.perform(get(VARIATION + "/" + id).header(HEADER, key)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response);
    }

    private UUID clientId(String name) {
        return jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, name);
    }

    private List<JsonNode> findings(JsonNode result, String ruleId) {
        List<JsonNode> found = new ArrayList<>();
        result.get("findings").forEach(finding -> {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                found.add(finding);
            }
        });
        return found;
    }

    // ---- the response ---------------------------------------------------------------------------------------------

    @Test
    void aFreshFamilyIsCheckedStoredAndReadBackInTheSameShape() throws Exception {
        String u = unique();
        JsonNode result = ok(KEY_1, amazonFamily(rulesets.ruleset(), u, "OTHER-" + u));

        assertThat(UUID.fromString(result.get("checkId").asText())).isNotNull();
        assertThat(result.get("marketplace").asText()).isEqualTo("AMAZON");
        assertThat(result.get("rulesetVersion").asText()).isEqualTo(rulesets.ruleset().version());
        assertThat(result.get("analysisVersion").asText()).matches("[0-9a-f]{64}");
        assertThat(result.get("cached").asBoolean()).isFalse();
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("failedRuleIds")).isEmpty();
        assertThat(result.get("disclaimer").asText()).isEqualTo(rulesets.ruleset().disclaimer());
        assertThat(Instant.parse(result.get("expiresAt").asText())).isAfter(Instant.parse(result.get("createdAt").asText()));
        assertThat(result.get("summary").get("errors").asInt()).isZero();
        assertThat(result.get("summary").get("warnings").asInt()).isEqualTo(1);
        assertThat(result.get("summary").get("infos").asInt()).isZero();

        assertThat(result.get("findings")).hasSize(1);
        JsonNode finding = result.get("findings").get(0);
        assertThat(finding.get("ruleId").asText()).isEqualTo(RuleIds.PARENT_CHILD_STRUCTURE);
        assertThat(finding.get("kind").asText()).isEqualTo(RuleIds.CHILD_PARENT_SKU_MISMATCH);
        assertThat(finding.get("severity").asText()).isEqualTo("WARNING");
        assertThat(finding.get("sourceStatus").asText()).isEqualTo("SECONDARY");
        assertThat(finding.get("field").asText()).isEqualTo("children[1].parent_sku");
        assertThat(finding.get("value").asText()).isEqualTo("OTHER-" + u);
        assertThat(finding.get("relatedFields")).extracting(JsonNode::asText).containsExactly("parent.sku");
        assertThat(finding.get("message").asText()).contains("OTHER-" + u, "P-" + u);
        assertThat(finding.get("suggestion").asText()).isNotBlank();

        assertThat(read(KEY_1, result.get("checkId").asText())).isEqualTo(result);
    }

    @Test
    void aCleanFamilyIsCompleteWithNoFinding() throws Exception {
        JsonNode result = ok(KEY_1, amazonFamily(rulesets.ruleset(), unique(), null));

        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("findings")).isEmpty();
        assertThat(result.get("summary").get("warnings").asInt()).isZero();
    }

    @Test
    void anEtsyListingGetsTheVariationLimitsAndNothingAboutAParent() throws Exception {
        JsonNode result = ok(KEY_1, etsyFamily(rulesets.ruleset(), unique()));

        assertThat(result.get("marketplace").asText()).isEqualTo("ETSY");
        assertThat(findings(result, RuleIds.ETSY_VARIATION_LIMITS)).extracting(f -> f.get("kind").asText())
                .containsExactly(RuleIds.TOO_MANY_VARIATION_TYPES, RuleIds.VARIATION_TYPE_NOT_SUPPORTED);
        assertThat(findings(result, RuleIds.PARENT_CHILD_STRUCTURE)).isEmpty();
        assertThat(result.get("summary").get("errors").asInt()).isZero();
    }

    // ---- the cache ------------------------------------------------------------------------------------------------

    @Test
    void theSameFamilyAgainIsServedFromTheCacheWithTheSameCheckId() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);

        JsonNode first = ok(KEY_1, body);
        JsonNode second = ok(KEY_1, body);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
        assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
        assertThat(second.get("findings")).isEqualTo(first.get("findings"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE client_id = ? AND input_hash = ?",
                Integer.class, clientId(CLIENT_1), CanonicalInput.of(normalizer.normalize("AMAZON",
                        MAPPER.treeToValue(body, VariationCheckRequest.class))).hash())).isEqualTo(1);
    }

    @Test
    void theMarketplaceCodeIsCaseInsensitiveAndKeysOfOneClientShareTheCache() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);
        JsonNode first = ok(KEY_1, body);

        body.put("marketplace", "amazon");
        JsonNode second = ok(KEY_1_SECOND, body);

        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
    }

    @Test
    void aChangedFamilyIsAnotherCheck() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);
        JsonNode first = ok(KEY_1, body);

        ((ObjectNode) body.get("children").get(0)).put("title", "Another title " + u);
        JsonNode second = ok(KEY_1, body);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId")).isNotEqualTo(first.get("checkId"));
    }

    @Test
    void anotherClientNeverReadsOrSharesAResult() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);
        JsonNode mine = ok(KEY_1, body);
        String id = mine.get("checkId").asText();

        mvc.perform(get(VARIATION + "/" + id).header(HEADER, KEY_2)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("variation-check-not-found"))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.requestId").isNotEmpty());
        JsonNode theirs = ok(KEY_2, body);

        assertThat(theirs.get("cached").asBoolean()).as("never answered from my cache").isFalse();
        assertThat(theirs.get("checkId")).isNotEqualTo(mine.get("checkId"));
        mvc.perform(get(VARIATION + "/" + theirs.get("checkId").asText()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownIdAnswersTheSame404AndANonUuidIsABadRequest() throws Exception {
        mvc.perform(get(VARIATION + "/" + UUID.randomUUID()).header(HEADER, KEY_1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("variation-check-not-found"));
        mvc.perform(get(VARIATION + "/not-a-uuid").header(HEADER, KEY_1)).andExpect(status().isBadRequest());
    }

    @Test
    void identicalRequestsAtTheSameMomentStoreOneResult() throws Exception {
        String u = unique();
        String body = MAPPER.writeValueAsString(amazonFamily(rulesets.ruleset(), u, "OTHER-" + u));
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> MAPPER.readTree(perform(KEY_1, body).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8)).get("checkId").asText());
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> future : pool.invokeAll(calls)) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE id = ?::uuid",
                    Integer.class, ids.iterator().next())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE client_id = ? AND created_at = "
                    + "(SELECT created_at FROM variation_check WHERE id = ?::uuid)", Integer.class, clientId(CLIENT_1),
                    ids.iterator().next())).as("one row for the one result").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- what is stored -------------------------------------------------------------------------------------------

    @Test
    void theSubmittedFamilyIsNotStoredOnlyItsHashAndTheBoundedEvidence() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);
        String id = ok(KEY_1, body).get("checkId").asText();

        String checkRow = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM variation_check c WHERE id = ?::uuid",
                String.class, id);
        List<String> findingRows = jdbc.queryForList(
                "SELECT to_jsonb(f)::text FROM variation_check_finding f WHERE check_id = ?::uuid", String.class, id);
        String everything = checkRow + String.join("\n", findingRows);

        assertThat(jdbc.queryForObject("SELECT input_hash FROM variation_check WHERE id = ?::uuid", String.class, id))
                .isEqualTo(CanonicalInput.of(normalizer.normalize("AMAZON",
                        MAPPER.treeToValue(body, VariationCheckRequest.class))).hash());
        // the titles and the child SKUs were submitted and are not evidence of the one finding
        assertThat(everything).doesNotContain("Retro Sunset Tee").doesNotContain("C1-" + u).doesNotContain("C2-" + u)
                .doesNotContain(MAPPER.writeValueAsString(body));
        // the evidence is: the wrong parent_sku, the parent's SKU in the message, and the field
        assertThat(everything).contains("OTHER-" + u).contains("children[1].parent_sku");
    }

    @Test
    void clientTextIsBoundedByTheRulesetInTheResponseAndInTheTable() throws Exception {
        String u = unique();
        int max = rulesets.ruleset().maxValueChars();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, null);
        ((ObjectNode) body.get("parent")).put("variation_theme", "T".repeat(max * 3));

        JsonNode result = ok(KEY_1, body);

        assertThat(result.get("findings")).isNotEmpty();
        result.get("findings").forEach(finding -> {
            if (!finding.get("value").isNull()) {
                assertThat(finding.get("value").asText().length()).isLessThanOrEqualTo(max);
            }
            assertThat(finding.get("message").asText()).doesNotContain("T".repeat(max + 1));
        });
        assertThat(jdbc.queryForObject("SELECT max(char_length(value)) FROM variation_check_finding WHERE check_id = ?::uuid",
                Integer.class, result.get("checkId").asText())).isLessThanOrEqualTo(max);
    }

    // ---- retention ------------------------------------------------------------------------------------------------

    @Test
    void anExpiredResultIsNotReadableNotCachedAndIsReplacedByTheNextRequest() throws Exception {
        String u = unique();
        ObjectNode body = amazonFamily(rulesets.ruleset(), u, "OTHER-" + u);
        JsonNode first = ok(KEY_1, body);
        String oldId = first.get("checkId").asText();

        jdbc.update("UPDATE variation_check SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' "
                + "WHERE id = ?::uuid", oldId);

        mvc.perform(get(VARIATION + "/" + oldId).header(HEADER, KEY_1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("variation-check-not-found"));
        JsonNode second = ok(KEY_1, body);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId")).isNotEqualTo(first.get("checkId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE id = ?::uuid", Integer.class, oldId))
                .as("the expired row and its findings were replaced").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM variation_check_finding WHERE check_id = ?::uuid",
                Integer.class, oldId)).isZero();
        mvc.perform(get(VARIATION + "/" + oldId).header(HEADER, KEY_1)).andExpect(status().isNotFound());
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    @Test
    void anUnknownMarketplaceAndAnUnreadableBodyAreBadRequests() throws Exception {
        perform(KEY_1, "{\"marketplace\":\"NOWHERE\",\"children\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-marketplace"));
        // the tests plan one marketplace (TestcontainersConfig): the same answer as the other endpoints give for it
        perform(KEY_1, "{\"marketplace\":\"WALMART\",\"children\":[]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("marketplace-not-yet-supported"));
        perform(KEY_1, "{\"children\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        perform(KEY_1, "not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void aMissingKeyIsRefusedLikeEveryOtherEndpoint() throws Exception {
        mvc.perform(post(VARIATION).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestWithNothingToCheckSucceedsAndSaysWhatIsMissing() throws Exception {
        JsonNode result = ok(KEY_1, MAPPER.readTree("{\"marketplace\":\"AMAZON\"}"));

        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("findings")).extracting(f -> f.get("kind").asText())
                .contains(RuleIds.PARENT_MISSING, RuleIds.NO_CHILDREN);
    }

    @Test
    void aVariationCheckLeavesTheResultTablesOfTheOtherEndpointsAlone() throws Exception {
        TestClient fresh = newClient(provisioning, clients, PlanCode.FREE);
        ok(fresh.key(), amazonFamily(rulesets.ruleset(), unique(), null));

        assertThat(countOf(jdbc, "variation_check", fresh.id())).isEqualTo(1);
        assertThat(countOf(jdbc, "listing_check", fresh.id())).isZero();
        assertThat(countOf(jdbc, "trademark_check", fresh.id())).isZero();
    }

    @Test
    void whilePlansAreOffAFreeClientCanCallAndNothingIsCounted() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        ok(free.key(), amazonFamily(rulesets.ruleset(), unique(), null));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM client_usage WHERE client_id = ?", Integer.class, free.id()))
                .isZero();
    }
}
