package com.thanhdatpb.listingguard.api.personalization;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1_SECOND;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.body;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.emoji;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.etsy;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.unique;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
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
import com.thanhdatpb.listingguard.personalization.CanonicalInput;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/personalization-screenings} and {@code GET /api/v1/personalization-screenings/{id}} (ADR-016, ADR-010)
 * with the DEMO catalog served: the character rules and the trademark scan at the HTTP level, the cache and client
 * isolation, retention, and that the text the buyer typed is not stored. Plans are off here (the default); the quota has its
 * own test, and a catalog that is not served has its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PersonalizationDemoCatalogTestConfig.class})
class PersonalizationScreeningApiIntegrationTest {

    private static final String SCREENINGS = "/api/v1/personalization-screenings";

    /** An invented word of the DEMO catalog (catalogs/demo/raw/DEMO-fictional-marks.jsonl). */
    private static final String DEMO_MARK = "Zibblefrax";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PersonalizationRulesets rulesets;

    @Autowired
    PersonalizationNormalizer normalizer;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    private ResultActions perform(String key, String json) throws Exception {
        return mvc.perform(post(SCREENINGS).header(HEADER, key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode ok(String key, JsonNode body) throws Exception {
        String response = perform(key, MAPPER.writeValueAsString(body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response);
    }

    private JsonNode read(String key, String id) throws Exception {
        String response = mvc.perform(get(SCREENINGS + "/" + id).header(HEADER, key)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response);
    }

    private UUID clientId(String name) {
        return jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, name);
    }

    private static List<JsonNode> findings(JsonNode result, String ruleId) {
        List<JsonNode> found = new ArrayList<>();
        result.get("findings").forEach(finding -> {
            if (finding.get("ruleId").asText().equals(ruleId)) {
                found.add(finding);
            }
        });
        return found;
    }

    // ---- the trademark part ---------------------------------------------------------------------------------------

    @Test
    void aCatalogMarkTypedAsAValueIsHighAndTheAnswerDoesNotRepeatTheText() throws Exception {
        String typed = DEMO_MARK.toUpperCase();

        JsonNode result = ok(KEY_1, body("ETSY", null, "team", typed));

        JsonNode trademark = result.get("trademark");
        assertThat(trademark.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(trademark.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(trademark.get("catalogVersion").asText()).isEqualTo(PersonalizationDemoCatalogTestConfig.catalogVersion());
        assertThat(trademark.get("rulesetVersion").asText()).isEqualTo("trademark-risk-2026-11b");
        assertThat(trademark.get("findings")).hasSize(1);
        JsonNode hit = trademark.get("findings").get(0);
        assertThat(hit.get("ruleId").asText()).isEqualTo("catalog_exact");
        assertThat(hit.get("risk").asText()).isEqualTo("HIGH");
        assertThat(hit.get("field").asText()).isEqualTo("personalization[0]");
        assertThat(hit.get("label").asText()).isEqualTo("team");
        assertThat(hit.get("start").asInt()).isZero();
        assertThat(hit.get("end").asInt()).isEqualTo(typed.length());
        assertThat(hit.get("mark").asText()).isEqualTo(DEMO_MARK);
        assertThat(hit.get("message").asText()).contains("'" + DEMO_MARK + "'").doesNotContain(typed);
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.toString()).doesNotContain(typed);
        assertThat(read(KEY_1, result.get("checkId").asText())).isEqualTo(result);
    }

    @Test
    void aPlainNameIsLowAndNothingIsFound() throws Exception {
        JsonNode result = ok(KEY_1, body("ETSY", null, "name", "MAX", "number", "10"));

        assertThat(result.get("findings")).isEmpty();
        assertThat(result.get("trademark").get("riskLevel").asText()).isEqualTo("LOW");
        assertThat(result.get("trademark").get("findings")).isEmpty();
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(result.get("printProfile").asText()).isEqualTo(rulesets.ruleset().defaultProfile());
        assertThat(result.get("rulesetVersion").asText()).isEqualTo(rulesets.ruleset().version());
        assertThat(result.get("disclaimer").asText()).isEqualTo(rulesets.ruleset().disclaimer());
        assertThat(result.get("analysisVersion").asText()).matches("[0-9a-f]{64}");
        assertThat(Instant.parse(result.get("expiresAt").asText())).isAfter(Instant.parse(result.get("createdAt").asText()));
    }

    // ---- the character rules --------------------------------------------------------------------------------------

    @Test
    void anEmojiIsUnsupportedWithTheExactUtf16Offset() throws Exception {
        String emoji = emoji();

        JsonNode result = ok(KEY_1, body("ETSY", null, "name", "Max " + emoji));

        JsonNode finding = findings(result, RuleIds.UNSUPPORTED_CHARACTERS).get(0);
        assertThat(finding.get("kind").asText()).isEqualTo(RuleIds.CHARACTER_NOT_ALLOWED);
        assertThat(finding.get("severity").asText()).isEqualTo("WARNING");
        assertThat(finding.get("sourceStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(finding.get("field").asText()).isEqualTo("personalization[0]");
        assertThat(finding.get("label").asText()).isEqualTo("name");
        assertThat(finding.get("start").asInt()).isEqualTo(4);
        assertThat(finding.get("end").asInt()).isEqualTo(6);
        assertThat(finding.get("value").asText()).isEqualTo(emoji);
        assertThat(finding.get("message").asText()).contains("U+1F600");
        assertThat(result.get("summary").get("warnings").asInt()).isEqualTo(1);
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void aVietnameseNameIsAWarningUnderTheBasicProfileAndPassesUnderTheExtendedOne() throws Exception {
        String name = "Đạt";

        JsonNode basic = ok(KEY_1, body("ETSY", "latin_basic", "name", name));
        JsonNode extended = ok(KEY_1, body("ETSY", "latin_extended", "name", name));

        assertThat(findings(basic, RuleIds.UNSUPPORTED_CHARACTERS)).hasSize(1);
        assertThat(findings(basic, RuleIds.UNSUPPORTED_CHARACTERS).get(0).get("severity").asText()).isEqualTo("WARNING");
        assertThat(basic.get("printProfile").asText()).isEqualTo("latin_basic");
        assertThat(extended.get("findings")).isEmpty();
        assertThat(extended.get("printProfile").asText()).isEqualTo("latin_extended");
    }

    @Test
    void aTextOverTheLimitIsTooLongWithTheLimitFromTheRuleset() throws Exception {
        int limit = rulesets.ruleset().profile(null).maxLength();

        JsonNode result = ok(KEY_1, etsy("A".repeat(limit + 5)));

        JsonNode finding = findings(result, RuleIds.TOO_LONG).get(0);
        assertThat(finding.get("severity").asText()).isEqualTo("WARNING");
        assertThat(finding.get("start").asInt()).isEqualTo(limit);
        assertThat(finding.get("end").asInt()).isEqualTo(limit + 5);
        assertThat(finding.get("value").isNull()).isTrue();
        assertThat(finding.get("message").asText()).contains("dài " + (limit + 5), "giới hạn " + limit);
    }

    @Test
    void aDoubleSpaceIsInfoAndTheTextIsNotTrimmedBeforeTheRulesSeeIt() throws Exception {
        JsonNode result = ok(KEY_1, body("ETSY", null, null, "  A  B "));

        assertThat(findings(result, RuleIds.WHITESPACE_ISSUE)).extracting(f -> f.get("kind").asText())
                .containsExactly(RuleIds.LEADING_SPACE, RuleIds.REPEATED_SPACE, RuleIds.TRAILING_SPACE);
        assertThat(findings(result, RuleIds.WHITESPACE_ISSUE)).extracting(f -> f.get("severity").asText()).containsOnly("INFO");
        assertThat(result.get("summary").get("infos").asInt()).isEqualTo(3);
        assertThat(result.get("summary").get("warnings").asInt()).isZero();
    }

    @Test
    void theOffsetsOfATrademarkHitAreInTheTextAsSentAfterLeadingSpaces() throws Exception {
        JsonNode result = ok(KEY_1, body("ETSY", null, null, "  " + DEMO_MARK.toLowerCase()));

        JsonNode hit = result.get("trademark").get("findings").get(0);
        assertThat(hit.get("start").asInt()).isEqualTo(2);
        assertThat(hit.get("end").asInt()).isEqualTo(2 + DEMO_MARK.length());
    }

    @Test
    void aRequestWithNoValuesIsCompleteAndEmpty() throws Exception {
        JsonNode result = ok(KEY_1, MAPPER.readTree("{\"marketplace\":\"ETSY\"}"));

        assertThat(result.get("findings")).isEmpty();
        assertThat(result.get("completeness").asText()).isEqualTo("COMPLETE");
    }

    // ---- the cache and client isolation ---------------------------------------------------------------------------

    @Test
    void theSameValuesAgainAreServedFromTheCacheWithTheSameCheckId() throws Exception {
        ObjectNode body = body("ETSY", null, "name", "Max " + emoji() + unique());

        JsonNode first = ok(KEY_1, body);
        JsonNode second = ok(KEY_1, body);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
        assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
        assertThat(second.get("findings")).isEqualTo(first.get("findings"));
        assertThat(second.get("trademark")).isEqualTo(first.get("trademark"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE client_id = ? AND input_hash = ?",
                Integer.class, clientId(CLIENT_1), CanonicalInput.of(normalizer.normalize(
                        MAPPER.treeToValue(body, PersonalizationScreeningRequest.class))).hash())).isEqualTo(1);
    }

    @Test
    void theMarketplaceCodeIsCaseInsensitiveAndKeysOfOneClientShareTheCache() throws Exception {
        ObjectNode body = body("ETSY", null, null, unique());
        JsonNode first = ok(KEY_1, body);

        body.put("marketplace", "etsy");
        JsonNode second = ok(KEY_1_SECOND, body);

        assertThat(second.get("cached").asBoolean()).isTrue();
        assertThat(second.get("checkId")).isEqualTo(first.get("checkId"));
    }

    @Test
    void aSpaceOrAnotherProfileOrAnotherMarketplaceIsAnotherScreening() throws Exception {
        String text = unique();
        JsonNode first = ok(KEY_1, body("ETSY", null, null, text));

        JsonNode spaced = ok(KEY_1, body("ETSY", null, null, text + " "));
        JsonNode otherProfile = ok(KEY_1, body("ETSY", "latin_extended", null, text));
        JsonNode otherMarketplace = ok(KEY_1, body("AMAZON", null, null, text));

        assertThat(List.of(spaced.get("cached").asBoolean(), otherProfile.get("cached").asBoolean(),
                otherMarketplace.get("cached").asBoolean())).containsOnly(false);
        assertThat(Set.of(first.get("checkId"), spaced.get("checkId"), otherProfile.get("checkId"),
                otherMarketplace.get("checkId"))).hasSize(4);
    }

    @Test
    void anotherClientNeverReadsOrSharesAResult() throws Exception {
        ObjectNode body = body("ETSY", null, null, unique());
        JsonNode mine = ok(KEY_1, body);
        String id = mine.get("checkId").asText();

        mvc.perform(get(SCREENINGS + "/" + id).header(HEADER, KEY_2)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("personalization-screening-not-found"))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.requestId").isNotEmpty());
        JsonNode theirs = ok(KEY_2, body);

        assertThat(theirs.get("cached").asBoolean()).as("never answered from my cache").isFalse();
        assertThat(theirs.get("checkId")).isNotEqualTo(mine.get("checkId"));
        mvc.perform(get(SCREENINGS + "/" + theirs.get("checkId").asText()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownIdAnswersTheSame404AndANonUuidIsABadRequest() throws Exception {
        mvc.perform(get(SCREENINGS + "/" + UUID.randomUUID()).header(HEADER, KEY_1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("personalization-screening-not-found"));
        mvc.perform(get(SCREENINGS + "/not-a-uuid").header(HEADER, KEY_1)).andExpect(status().isBadRequest());
    }

    @Test
    void identicalRequestsAtTheSameMomentStoreOneResult() throws Exception {
        String json = MAPPER.writeValueAsString(body("ETSY", null, null, unique()));
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> MAPPER.readTree(perform(KEY_1, json).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8)).get("checkId").asText());
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> future : pool.invokeAll(calls)) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE id = ?::uuid",
                    Integer.class, ids.iterator().next())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- what is stored -------------------------------------------------------------------------------------------

    @Test
    void theTextTheBuyerTypedIsNotStoredOnlyItsHashAndTheBoundedEvidence() throws Exception {
        String u = unique();
        String emoji = emoji();
        String typed = u + "  " + emoji;
        ObjectNode body = body("ETSY", null, "name", typed, "team", DEMO_MARK.toUpperCase());
        JsonNode result = ok(KEY_1, body);
        String id = result.get("checkId").asText();

        String checkRow = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM personalization_screening c WHERE id = ?::uuid",
                String.class, id);
        List<String> findingRows = jdbc.queryForList(
                "SELECT to_jsonb(f)::text FROM personalization_screening_finding f WHERE check_id = ?::uuid", String.class, id);
        String everything = checkRow + String.join("\n", findingRows);

        assertThat(jdbc.queryForObject("SELECT input_hash FROM personalization_screening WHERE id = ?::uuid", String.class, id))
                .isEqualTo(CanonicalInput.of(normalizer.normalize(
                        MAPPER.treeToValue(body, PersonalizationScreeningRequest.class))).hash());
        // neither value, nor the part of the first that is not evidence, nor the second as the buyer typed it
        assertThat(everything).doesNotContain(typed).doesNotContain(u).doesNotContain(DEMO_MARK.toUpperCase());
        assertThat(result.toString()).doesNotContain(u).doesNotContain(DEMO_MARK.toUpperCase());
        // what is kept: the offending emoji, the catalog mark, the labels, the offsets, the profile
        assertThat(everything).contains(emoji).contains(DEMO_MARK).contains("\"label\": \"name\"")
                .contains("personalization").contains("latin_basic");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening_finding WHERE check_id = ?::uuid "
                + "AND char_length(value) > ?", Integer.class, id, rulesets.ruleset().maxValueChars())).isZero();
    }

    @Test
    void aLongRunOfUnsupportedCharactersIsBoundedInTheResponseAndInTheTable() throws Exception {
        int max = rulesets.ruleset().maxValueChars();

        JsonNode result = ok(KEY_1, body("ETSY", null, null, emoji().repeat(max * 3)));

        JsonNode finding = findings(result, RuleIds.UNSUPPORTED_CHARACTERS).get(0);
        assertThat(finding.get("value").asText().codePointCount(0, finding.get("value").asText().length())).isEqualTo(max);
        assertThat(jdbc.queryForObject("SELECT max(char_length(value)) FROM personalization_screening_finding "
                + "WHERE check_id = ?::uuid", Integer.class, result.get("checkId").asText())).isLessThanOrEqualTo(max);
    }

    @Test
    void controlCharactersAndLoneSurrogatesDoNotBreakTheStorage() throws Exception {
        JsonNode result = ok(KEY_1, MAPPER.readTree("{\"marketplace\":\"ETSY\",\"values\":[{\"label\":\"n\\u0000x\","
                + "\"text\":\"A\\u0000B\\ud800C\"}]}"));

        assertThat(findings(result, RuleIds.UNSUPPORTED_CHARACTERS)).hasSize(2);
        assertThat(read(KEY_1, result.get("checkId").asText())).isEqualTo(result);
    }

    // ---- retention ------------------------------------------------------------------------------------------------

    @Test
    void anExpiredResultIsNotReadableNotCachedAndIsReplacedByTheNextRequest() throws Exception {
        ObjectNode body = body("ETSY", null, null, unique() + emoji());
        JsonNode first = ok(KEY_1, body);
        String oldId = first.get("checkId").asText();

        jdbc.update("UPDATE personalization_screening SET created_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 day' WHERE id = ?::uuid", oldId);

        mvc.perform(get(SCREENINGS + "/" + oldId).header(HEADER, KEY_1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("personalization-screening-not-found"));
        JsonNode second = ok(KEY_1, body);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId")).isNotEqualTo(first.get("checkId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE id = ?::uuid", Integer.class,
                oldId)).as("the expired row and its findings were replaced").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening_finding WHERE check_id = ?::uuid",
                Integer.class, oldId)).isZero();
        mvc.perform(get(SCREENINGS + "/" + oldId).header(HEADER, KEY_1)).andExpect(status().isNotFound());
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    @Test
    void anUnknownPrintProfileIsABadRequestThatNamesTheDeclaredOnesAndStoresNothing() throws Exception {
        TestClient fresh = newClient(provisioning, clients, PlanCode.FREE);

        perform(fresh.key(), MAPPER.writeValueAsString(body("ETSY", "latin_fancy", null, "MAX")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown-print-profile"))
                .andExpect(jsonPath("$.known[0]").value(rulesets.ruleset().defaultProfile()))
                .andExpect(jsonPath("$.known.length()").value(rulesets.ruleset().profiles().size()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(countOf(jdbc, "personalization_screening", fresh.id())).isZero();
    }

    @Test
    void anUnknownMarketplaceAndAnUnreadableBodyAreBadRequests() throws Exception {
        perform(KEY_1, "{\"marketplace\":\"NOWHERE\",\"values\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-marketplace"));
        perform(KEY_1, "{\"marketplace\":\"WALMART\",\"values\":[]}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("marketplace-not-yet-supported"));
        perform(KEY_1, "{\"values\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        perform(KEY_1, "not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void aMissingKeyIsRefusedLikeEveryOtherEndpoint() throws Exception {
        mvc.perform(post(SCREENINGS).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aScreeningLeavesTheResultTablesOfTheOtherEndpointsAlone() throws Exception {
        TestClient fresh = newClient(provisioning, clients, PlanCode.FREE);

        ok(fresh.key(), etsy(unique()));

        assertThat(countOf(jdbc, "personalization_screening", fresh.id())).isEqualTo(1);
        assertThat(countOf(jdbc, "listing_check", fresh.id())).isZero();
        assertThat(countOf(jdbc, "trademark_check", fresh.id())).isZero();
        assertThat(countOf(jdbc, "variation_check", fresh.id())).isZero();
    }

    @Test
    void whilePlansAreOffAFreeClientCanCallAndNothingIsCounted() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);

        ok(free.key(), etsy(unique()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM client_usage WHERE client_id = ?", Integer.class, free.id()))
                .isZero();
    }
}
