package com.thanhdatpb.listingguard.api.ruleset;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Product type overrides through the endpoint (ADR-006). The real Amazon ruleset overrides no product type yet, so this test
 * loads the rule engine's test ruleset ({@code amazon-test-categories.yaml}: made-up product types TESTBAG and TESTMUG) as the
 * active Amazon ruleset. The numbers are read from that ruleset, none is repeated here, and the answer is held to the
 * check: a title one over the declared limit is a finding, one at the limit is not.
 */
@SpringBootTest(properties = {
        "listing-guard.rulesets.locations[0]=classpath:rulesets/etsy-2026-11.yaml",
        "listing-guard.rulesets.locations[1]=file:../rule-engine/src/test/resources/rulesets/amazon-test-categories.yaml"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RulesetLimitsOverrideIntegrationTest {

    private static final String AMAZON = "/api/v1/rulesets/AMAZON/limits";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ActiveRulesets rulesets;

    private RulesetConfig config() {
        RulesetConfig config = rulesets.get("AMAZON").config();
        assertThat(config.version()).as("the test ruleset is the active Amazon ruleset").isEqualTo("test-categories-1");
        return config;
    }

    private JsonNode json(String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header(HEADER, KEY_1)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
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

    @Test
    void theTestRulesetFileIsThere() {
        assertThat(Path.of("..", "rule-engine", "src", "test", "resources", "rulesets", "amazon-test-categories.yaml")).exists();
    }

    @Test
    void withoutAProductTypeTheDefaultsAreReturned() throws Exception {
        RuleSettings title = config().rule("title_length");
        JsonNode body = json(AMAZON);

        assertThat(body.get("productType").isNull()).isTrue();
        assertThat(limit(body, "title_length").get("value").asInt()).isEqualTo(title.intParam("max_length"));
        assertThat(limit(body, "title_length").get("severity").asText()).isEqualTo(title.severity().name());
    }

    @Test
    void aProductTypeWithAnOverrideGetsTheOverrideValueSeverityAndSource() throws Exception {
        RulesetConfig config = config();
        RuleSettings defaults = config.rule("title_length");
        RuleSettings bag = config.rule("title_length", "TESTBAG");
        assertThat(bag.intParam("max_length")).as("an override really differs").isNotEqualTo(defaults.intParam("max_length"));

        JsonNode body = json(AMAZON + "?productType=TESTBAG");
        JsonNode title = limit(body, "title_length");

        assertThat(body.get("productType").asText()).isEqualTo("TESTBAG");
        assertThat(title.get("value").asInt()).isEqualTo(bag.intParam("max_length"));
        assertThat(title.get("severity").asText()).isEqualTo(bag.severity().name()).isNotEqualTo(defaults.severity().name());
        assertThat(title.get("sourceStatus").asText()).isEqualTo(bag.source().status().name());
    }

    @Test
    void theProductTypeIsMatchedIgnoringCaseAndBlanksAndIsEchoedAsSent() throws Exception {
        int expected = config().rule("title_length", "TESTMUG").intParam("max_length");

        // MockMvc encodes the URL template itself, so the blanks are written as blanks, not as %20
        JsonNode body = json(AMAZON + "?productType= testmug ");

        assertThat(limit(body, "title_length").get("value").asInt()).isEqualTo(expected);
        assertThat(body.get("productType").asText()).isEqualTo("testmug");
    }

    @Test
    void anOverrideCanSwitchARuleOffOrOnForOneProductType() throws Exception {
        RulesetConfig config = config();

        JsonNode defaults = json(AMAZON);
        JsonNode mug = json(AMAZON + "?productType=TESTMUG");

        assertThat(limit(defaults, "bullet_count").get("enabled").asBoolean()).isEqualTo(config.rule("bullet_count").enabled()).isTrue();
        assertThat(limit(mug, "bullet_count").get("enabled").asBoolean()).isEqualTo(config.rule("bullet_count", "TESTMUG").enabled())
                .isFalse();
        assertThat(limit(defaults, "bullet_length").get("enabled").asBoolean()).isEqualTo(config.rule("bullet_length").enabled())
                .isFalse();
        JsonNode bulletLength = limit(mug, "bullet_length");
        assertThat(bulletLength.get("enabled").asBoolean()).isTrue();
        assertThat(bulletLength.get("value").asInt()).isEqualTo(config.rule("bullet_length", "TESTMUG").intParam("max_length"));
    }

    @Test
    void aProductTypeWithoutAnOverrideGetsTheDefaults() throws Exception {
        JsonNode defaults = json(AMAZON);

        assertThat(json(AMAZON + "?productType=SHOES").get("limits")).isEqualTo(defaults.get("limits"));
    }

    @Test
    void theETagDiffersPerProductTypeBecauseTheLimitsDo() throws Exception {
        String defaults = mvc.perform(get(AMAZON).header(HEADER, KEY_1)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String bag = mvc.perform(get(AMAZON + "?productType=TESTBAG").header(HEADER, KEY_1)).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);

        assertThat(bag).isNotEqualTo(defaults);
    }

    /** The number the endpoint returns for a product type is the one the check enforces, with the severity it returns. */
    @Test
    void theCheckEnforcesTheLimitTheEndpointReturnsForTheProductType() throws Exception {
        for (String productType : List.of("TESTBAG", "TESTMUG", "SHOES")) {
            JsonNode title = limit(json(AMAZON + "?productType=" + productType), "title_length");
            int max = title.get("value").asInt();

            assertThat(titleFinding(productType, "a".repeat(max), "at-" + productType)).as(productType + " at the limit").isNull();
            JsonNode over = titleFinding(productType, "a".repeat(max + 1), "over-" + productType);
            assertThat(over).as(productType + " one over the limit").isNotNull();
            assertThat(over.get("severity").asText()).isEqualTo(title.get("severity").asText());
        }
    }

    private JsonNode titleFinding(String productType, String title, String brand) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("marketplace", "AMAZON", "listing", java.util.Map.of(
                "title", title, "brand", brand, "product_type", productType)));
        JsonNode result = mapper.readTree(mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode finding : result.get("findings")) {
            if (finding.get("ruleId").asText().equals("title_length")) {
                return finding;
            }
        }
        return null;
    }
}
