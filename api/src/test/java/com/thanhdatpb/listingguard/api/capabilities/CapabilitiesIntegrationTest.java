package com.thanhdatpb.listingguard.api.capabilities;

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
import com.thanhdatpb.listingguard.api.personalization.PersonalizationRulesets;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.variation.VariationRulesets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/capabilities} on a default deployment (no trademark catalog, plans off, fixes off): it needs a key, it is the
 * same for every client, it is read only, and every version it names is the one the checks use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CapabilitiesIntegrationTest {

    private static final String PATH = "/api/v1/capabilities";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ActiveRulesets listingRules;

    @Autowired
    TrademarkRulesets trademark;

    @Autowired
    VariationRulesets variation;

    @Autowired
    PersonalizationRulesets personalization;

    private String raw(String key) throws Exception {
        return mvc.perform(get(PATH).header(HEADER, key)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json() throws Exception {
        return mapper.readTree(raw(KEY_1));
    }

    private static JsonNode pack(JsonNode body, String id) {
        List<JsonNode> found = new ArrayList<>();
        body.get("packs").forEach(p -> {
            if (p.get("id").asText().equals(id)) {
                found.add(p);
            }
        });
        assertThat(found).as("pack " + id).hasSize(1);
        return found.get(0);
    }

    @Test
    void itNeedsAnApiKeyLikeEveryEndpoint() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("ApiKey")));
        mvc.perform(get(PATH).header(HEADER, "w".repeat(40))).andExpect(status().isUnauthorized());
    }

    @Test
    void everyClientGetsTheSameAnswer() throws Exception {
        assertThat(raw(KEY_2)).isEqualTo(raw(KEY_1));
    }

    @Test
    void itIsReadOnly() throws Exception {
        mvc.perform(post(PATH).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("method-not-allowed"));
    }

    @Test
    void theExampleOfTheSpecIsWhatADefaultDeploymentReallyAnswers() throws Exception {
        assertThat(json()).isEqualTo(mapper.readTree(ApiExamples.CAPABILITIES));
    }

    @Test
    void theVersionsAreTheOnesTheChecksUse() throws Exception {
        JsonNode body = json();

        assertThat(pack(body, "listing-rules").get("rulesetVersions").get("ETSY").asText())
                .isEqualTo(listingRules.get("ETSY").config().version());
        assertThat(pack(body, "listing-rules").get("rulesetVersions").get("AMAZON").asText())
                .isEqualTo(listingRules.get("AMAZON").config().version());
        for (String marketplace : List.of("ETSY", "AMAZON")) {
            assertThat(pack(body, "trademark-risk").get("rulesetVersions").get(marketplace).asText())
                    .isEqualTo(trademark.ruleset().version());
            assertThat(pack(body, "variation-checks").get("rulesetVersions").get(marketplace).asText())
                    .isEqualTo(variation.ruleset().version());
            assertThat(pack(body, "personalization-screening").get("rulesetVersions").get(marketplace).asText())
                    .isEqualTo(personalization.ruleset().version());
        }
        List<String> ids = new ArrayList<>();
        body.get("packs").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).isSorted().doesNotContain("batch", "policy-analyzer", "sheet-connector");
    }

    @Test
    void withNoCatalogTheTrademarkPackIsNotAvailableAndThePlansAndFixesAreOff() throws Exception {
        JsonNode body = json();

        assertThat(body.at("/trademarkCatalog/state").asText()).isEqualTo("DISABLED");
        assertThat(body.at("/trademarkCatalog/demo").asBoolean()).isFalse();
        assertThat(pack(body, "trademark-risk").get("available").asBoolean()).isFalse();
        assertThat(pack(body, "listing-rules").get("available").asBoolean()).isTrue();
        assertThat(body.at("/plans/enforced").asBoolean()).isFalse();
        assertThat(body.at("/fixes/enabled").asBoolean()).isFalse();
    }

    @Test
    void theAnswerHasExactlyTheDocumentedMembersAndNoClientOrSecretData() throws Exception {
        JsonNode body = json();

        assertThat(body.fieldNames()).toIterable().containsExactly("packs", "trademarkCatalog", "plans", "fixes");
        assertThat(body.get("trademarkCatalog").fieldNames()).toIterable().containsExactly("state", "demo");
        assertThat(body.get("plans").fieldNames()).toIterable().containsExactly("enforced");
        assertThat(body.get("fixes").fieldNames()).toIterable().containsExactly("enabled");
        body.get("packs").forEach(p -> assertThat(p.fieldNames()).toIterable()
                .containsExactly("id", "available", "rulesetVersions"));
        assertThat(raw(KEY_1)).doesNotContain(KEY_1).doesNotContain(KEY_2).doesNotContain("jdbc").doesNotContain("password")
                .doesNotContain("classpath:");
    }

    @Test
    void theAnswerCarriesAnETagAndAPrivateCacheLifetime() throws Exception {
        mvc.perform(get(PATH).header(HEADER, KEY_1)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, Matchers.matchesPattern("\"[0-9a-f]{64}\"")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.allOf(Matchers.containsString("max-age="),
                        Matchers.containsString("private"))));
    }

    @Test
    void aMatchingIfNoneMatchIsA304WithNoBody() throws Exception {
        String etag = mvc.perform(get(PATH).header(HEADER, KEY_1)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get(PATH).header(HEADER, KEY_1).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }
}
