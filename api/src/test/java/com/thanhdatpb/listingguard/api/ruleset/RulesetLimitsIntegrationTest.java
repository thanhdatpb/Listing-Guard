package com.thanhdatpb.listingguard.api.ruleset;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /api/v1/rulesets/{marketplace}/limits} as a client sees it: it needs a key, it answers the numbers of the loaded
 * ruleset in the unit each rule counts, a product type without an override gets the defaults, an unknown marketplace is a 404
 * problem, and the answer is cacheable. Every number is read from the active ruleset, none is repeated here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RulesetLimitsIntegrationTest {

    private static final String ETSY = "/api/v1/rulesets/ETSY/limits";
    private static final String AMAZON = "/api/v1/rulesets/AMAZON/limits";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ActiveRulesets rulesets;

    @Autowired
    RuleEngine engine;

    private ResultActions read(String path) throws Exception {
        return mvc.perform(get(path).header(HEADER, KEY_1));
    }

    private JsonNode json(String path) throws Exception {
        return mapper.readTree(read(path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode limit(JsonNode body, String ruleId) {
        List<JsonNode> found = new ArrayList<>();
        body.get("limits").forEach(l -> {
            if (l.get("ruleId").asText().equals(ruleId)) {
                found.add(l);
            }
        });
        assertThat(found).as("limits of " + ruleId).hasSize(1);
        return found.get(0);
    }

    // ---- authentication -------------------------------------------------------------------------------

    @Test
    void itNeedsAnApiKeyLikeEveryEndpoint() throws Exception {
        mvc.perform(get(ETSY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("ApiKey")));
        mvc.perform(get(ETSY).header(HEADER, "w".repeat(40))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/rulesets/WALMART/limits")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClientWithAKeyGetsTheSameAnswer() throws Exception {
        String first = mvc.perform(get(ETSY).header(HEADER, KEY_1)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get(ETSY).header(HEADER, KEY_2)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void itIsReadOnly() throws Exception {
        mvc.perform(post(ETSY).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    // ---- Etsy -----------------------------------------------------------------------------------------

    @Test
    void etsyAnswersTheNumbersOfTheActiveRuleset() throws Exception {
        RulesetConfig etsy = rulesets.get("ETSY").config();
        JsonNode body = json(ETSY);

        assertThat(body.get("marketplace").asText()).isEqualTo("ETSY");
        assertThat(body.get("rulesetVersion").asText()).isEqualTo(etsy.version());
        assertThat(body.has("productType")).as("productType is present, null when not asked for").isTrue();
        assertThat(body.get("productType").isNull()).isTrue();

        JsonNode title = limit(body, "title_length");
        assertThat(title.get("field").asText()).isEqualTo("title");
        assertThat(title.get("kind").asText()).isEqualTo("MAX_LENGTH");
        assertThat(title.get("unit").asText()).isEqualTo("CODE_POINTS");
        assertThat(title.get("value").asInt()).isEqualTo(etsy.rule("title_length").intParam("max_length"));
        assertThat(title.get("severity").asText()).isEqualTo(etsy.rule("title_length").severity().name());
        assertThat(title.get("sourceStatus").asText()).isEqualTo(etsy.rule("title_length").source().status().name());
        assertThat(title.get("enabled").asBoolean()).isEqualTo(etsy.rule("title_length").enabled());

        JsonNode count = limit(body, "tag_count");
        assertThat(count.get("field").asText()).isEqualTo("tags");
        assertThat(count.get("kind").asText()).isEqualTo("MAX_COUNT");
        assertThat(count.get("unit").asText()).isEqualTo("ITEMS");
        assertThat(count.get("value").asInt()).isEqualTo(etsy.rule("tag_count").intParam("max_count"));

        JsonNode tag = limit(body, "tag_length");
        assertThat(tag.get("field").asText()).isEqualTo("tags");
        assertThat(tag.get("kind").asText()).isEqualTo("MAX_LENGTH");
        assertThat(tag.get("unit").asText()).isEqualTo("CODE_POINTS");
        assertThat(tag.get("value").asInt()).isEqualTo(etsy.rule("tag_length").intParam("max_length"));
    }

    @Test
    void theMarketplaceIsCaseInsensitiveAndAnswersInUpperCase() throws Exception {
        JsonNode body = json("/api/v1/rulesets/etsy/limits");

        assertThat(body.get("marketplace").asText()).isEqualTo("ETSY");
        assertThat(body).isEqualTo(json(ETSY));
    }

    @Test
    void theEtsyExampleOfTheSpecIsWhatTheEndpointReallyAnswers() throws Exception {
        assertThat(json(ETSY)).isEqualTo(mapper.readTree(ApiExamples.RULESET_LIMITS_ETSY));
    }

    // ---- Amazon ---------------------------------------------------------------------------------------

    @Test
    void amazonSearchTermsAreCountedInUtf8BytesAndASwitchedOffRuleIsListedAsDisabled() throws Exception {
        RulesetConfig amazon = rulesets.get("AMAZON").config();
        JsonNode body = json(AMAZON);

        assertThat(body.get("marketplace").asText()).isEqualTo("AMAZON");
        assertThat(body.get("rulesetVersion").asText()).isEqualTo(amazon.version());

        JsonNode terms = limit(body, "search_terms_length");
        assertThat(terms.get("field").asText()).isEqualTo("search_terms");
        assertThat(terms.get("kind").asText()).isEqualTo("MAX_BYTES");
        assertThat(terms.get("unit").asText()).isEqualTo("UTF8_BYTES");
        assertThat(terms.get("value").asInt()).isEqualTo(amazon.rule("search_terms_length").intParam("max_bytes"));

        JsonNode title = limit(body, "title_length");
        assertThat(title.get("unit").asText()).isEqualTo("CODE_POINTS");
        assertThat(title.get("value").asInt()).isEqualTo(amazon.rule("title_length").intParam("max_length"));

        JsonNode bullets = limit(body, "bullet_count");
        assertThat(bullets.get("field").asText()).isEqualTo("bullet_points");
        assertThat(bullets.get("unit").asText()).isEqualTo("ITEMS");

        // bullet_length is in the ruleset with enabled: false; it is listed, not hidden
        JsonNode bulletLength = limit(body, "bullet_length");
        assertThat(bulletLength.get("enabled").asBoolean()).isEqualTo(amazon.rule("bullet_length").enabled());
        assertThat(bulletLength.get("value").asInt()).isEqualTo(amazon.rule("bullet_length").intParam("max_length"));
    }

    @Test
    void amazonTitleAndItemHighlightsAreTheLimitsOfTheCurrentRulesetRead() throws Exception {
        RulesetConfig amazon = rulesets.get("AMAZON").config();
        JsonNode body = json(AMAZON);

        // the ruleset in force is amazon-2026-12b (75-character title, Item Highlights, Amazon Custom); the numbers are read from it
        assertThat(amazon.version()).isEqualTo("amazon-2026-12b");
        assertThat(body.get("rulesetVersion").asText()).isEqualTo("amazon-2026-12b");

        JsonNode title = limit(body, "title_length");
        assertThat(title.get("field").asText()).isEqualTo("title");
        assertThat(title.get("kind").asText()).isEqualTo("MAX_LENGTH");
        assertThat(title.get("value").asInt()).isEqualTo(amazon.rule("title_length").intParam("max_length"));

        JsonNode highlights = limit(body, "item_highlights_length");
        assertThat(highlights.get("field").asText()).isEqualTo("item_highlights");
        assertThat(highlights.get("kind").asText()).isEqualTo("MAX_LENGTH");
        assertThat(highlights.get("unit").asText()).isEqualTo("CODE_POINTS");
        assertThat(highlights.get("value").asInt()).isEqualTo(amazon.rule("item_highlights_length").intParam("max_length"));
        assertThat(highlights.get("severity").asText()).isEqualTo(amazon.rule("item_highlights_length").severity().name());
        assertThat(highlights.get("sourceStatus").asText())
                .isEqualTo(amazon.rule("item_highlights_length").source().status().name());
        assertThat(highlights.get("enabled").asBoolean()).isEqualTo(amazon.rule("item_highlights_length").enabled());
    }

    @Test
    void amazonCustomizationLimitsAreTheRulesetNumbersInItems() throws Exception {
        RulesetConfig amazon = rulesets.get("AMAZON").config();
        JsonNode body = json(AMAZON);

        assertThat(amazon.version()).isEqualTo("amazon-2026-12b");
        for (String[] expected : new String[][] {
                {"customization_text_input_count", "customization.surfaces[].text_inputs"},
                {"customization_dropdown_options", "customization.surfaces[].dropdowns[].options"},
                {"customization_image_input_count", "customization.surfaces[].image_inputs"}}) {
            String ruleId = expected[0];
            JsonNode limit = limit(body, ruleId);
            assertThat(limit.get("field").asText()).as(ruleId).isEqualTo(expected[1]);
            assertThat(limit.get("kind").asText()).as(ruleId).isEqualTo("MAX_COUNT");
            assertThat(limit.get("unit").asText()).as(ruleId).isEqualTo("ITEMS");
            assertThat(limit.get("value").asInt()).as(ruleId).isEqualTo(amazon.rule(ruleId).intParam("max_count"));
            assertThat(limit.get("severity").asText()).as(ruleId).isEqualTo(amazon.rule(ruleId).severity().name());
            assertThat(limit.get("sourceStatus").asText()).as(ruleId)
                    .isEqualTo(amazon.rule(ruleId).source().status().name());
            assertThat(limit.get("enabled").asBoolean()).as(ruleId).isTrue();
        }
        // the two rules without a number of their own declare no limit
        body.get("limits").forEach(limit -> assertThat(limit.get("ruleId").asText())
                .isNotIn("customization_text_input_max_chars", "customization_free_text_sensitive"));
    }

    // ---- generic: whatever ruleset is registered --------------------------------------------------------

    @Test
    void theAnswerIsExactlyWhatTheRulesDeclareForEveryActiveMarketplace() throws Exception {
        for (String marketplace : rulesets.marketplaces(RulesetConfig.DEFAULT_PACK_ID)) {
            RulesetConfig config = rulesets.get(marketplace).config();
            List<RuleLimit> expected = engine.limits(config, null);
            JsonNode actual = json("/api/v1/rulesets/" + marketplace + "/limits").get("limits");

            assertThat(actual).as(marketplace).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                RuleLimit limit = expected.get(i);
                JsonNode node = actual.get(i);
                assertThat(node.get("field").asText()).isEqualTo(limit.field());
                assertThat(node.get("ruleId").asText()).isEqualTo(limit.ruleId());
                assertThat(node.get("kind").asText()).isEqualTo(limit.kind().name());
                assertThat(node.get("value").asInt()).isEqualTo(limit.value());
                assertThat(node.get("unit").asText()).isEqualTo(limit.unit().name());
                assertThat(node.get("severity").asText()).isEqualTo(limit.severity().name());
                assertThat(node.get("sourceStatus").asText()).isEqualTo(limit.sourceStatus().name());
                assertThat(node.get("enabled").asBoolean()).isEqualTo(limit.enabled());
            }
        }
    }

    @Test
    void everyKindAndUnitIsOneOfTheDocumentedOnes() throws Exception {
        List<String> kinds = java.util.Arrays.stream(Limit.Kind.values()).map(Enum::name).toList();
        List<String> units = java.util.Arrays.stream(Limit.Unit.values()).map(Enum::name).toList();
        for (String marketplace : rulesets.marketplaces(RulesetConfig.DEFAULT_PACK_ID)) {
            json("/api/v1/rulesets/" + marketplace + "/limits").get("limits").forEach(limit -> {
                assertThat(kinds).contains(limit.get("kind").asText());
                assertThat(units).contains(limit.get("unit").asText());
            });
        }
    }

    // ---- product type ---------------------------------------------------------------------------------

    @Test
    void aProductTypeWithoutAnOverrideGetsTheDefaultsAndIsEchoedAsSent() throws Exception {
        RulesetConfig amazon = rulesets.get("AMAZON").config();
        String unknown = "NO-SUCH-PRODUCT-TYPE";
        assertThat(amazon.coversCategory(unknown)).as("a product type the ruleset does not override").isFalse();
        JsonNode defaults = json(AMAZON);

        JsonNode body = json(AMAZON + "?productType=" + unknown);

        assertThat(body.get("productType").asText()).isEqualTo(unknown);
        assertThat(body.get("limits")).isEqualTo(defaults.get("limits"));
        assertThat(body.get("rulesetVersion")).isEqualTo(defaults.get("rulesetVersion"));
    }

    @Test
    void aBlankProductTypeIsNoProductTypeAndAnotherCaseIsTrimmedButNotChanged() throws Exception {
        assertThat(json(AMAZON + "?productType=").get("productType").isNull()).isTrue();
        // MockMvc encodes the URL template itself, so the blanks are written as blanks, not as %20
        assertThat(json(AMAZON + "?productType=  ").get("productType").isNull()).isTrue();
        assertThat(json(AMAZON + "?productType= luggage ").get("productType").asText()).isEqualTo("luggage");
    }

    @Test
    void aProductTypeOnEtsyChangesNoLimit() throws Exception {
        JsonNode withType = json(ETSY + "?productType=LUGGAGE");

        assertThat(withType.get("limits")).isEqualTo(json(ETSY).get("limits"));
        assertThat(withType.get("productType").asText()).isEqualTo("LUGGAGE");
    }

    // ---- unknown marketplace --------------------------------------------------------------------------

    @Test
    void aMarketplaceWithoutARulesetIsA404ProblemWithAStableCode() throws Exception {
        for (String unknown : List.of("WALMART", "nope", "listing-rules")) {
            read("/api/v1/rulesets/" + unknown + "/limits")
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", Matchers.startsWith("application/problem+json")))
                    .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:ruleset-not-found"))
                    .andExpect(jsonPath("$.code").value("ruleset-not-found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.detail").value(Matchers.allOf(Matchers.containsString(unknown),
                            Matchers.containsString("ETSY"), Matchers.containsString("AMAZON"))))
                    .andExpect(jsonPath("$.instance").value("/api/v1/rulesets/" + unknown + "/limits"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
    }

    @Test
    void theTrademarkPackIsNotATextRulesetSoItsMarketplaceIsStillAnswered() throws Exception {
        // the trademark ruleset is registered for ETSY and AMAZON under another pack; this endpoint reads only listing-rules
        JsonNode body = json(ETSY);

        assertThat(body.get("limits")).extracting(l -> l.get("ruleId").asText())
                .noneMatch(id -> id.startsWith("catalog_") || id.contains("trademark"));
    }

    // ---- cache ----------------------------------------------------------------------------------------

    @Test
    void theAnswerCarriesAnETagAndAPrivateCacheLifetime() throws Exception {
        read(ETSY).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, Matchers.matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.allOf(Matchers.containsString("max-age="),
                        Matchers.containsString("private"))));
    }

    @Test
    void aMatchingIfNoneMatchIsA304WithNoBodyAndAnotherOneIsA200() throws Exception {
        String etag = read(ETSY).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get(ETSY).header(HEADER, KEY_1).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string("Content-Length", Matchers.anyOf(Matchers.nullValue(), Matchers.is("0"))));
        mvc.perform(get(ETSY).header(HEADER, KEY_1).header(HttpHeaders.IF_NONE_MATCH, "\"" + "0".repeat(64) + "\""))
                .andExpect(status().isOk());
    }

    @Test
    void theETagChangesWithTheMarketplaceAndTheProductType() throws Exception {
        String etsy = read(ETSY).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String amazon = read(AMAZON).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String amazonLuggage = read(AMAZON + "?productType=LUGGAGE").andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(List.of(etsy, amazon, amazonLuggage)).doesNotHaveDuplicates();
        assertThat(read(AMAZON).andReturn().getResponse().getHeader(HttpHeaders.ETAG)).as("stable").isEqualTo(amazon);
    }
}
