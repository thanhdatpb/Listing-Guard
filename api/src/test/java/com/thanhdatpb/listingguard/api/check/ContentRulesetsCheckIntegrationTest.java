package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/checks} with the opt-in ruleset versions of ADR-039 (etsy-2026-12b, amazon-2026-12d): the response has the same shape as ever, the
 * listing it describes gets the content rules' findings with the version of the ruleset that made them, a clean listing has none, and the comparison
 * with an original (workspace only) is never part of it. Nothing in a response says where in the text a finding is.
 */
@SpringBootTest(properties = {
        "listing-guard.rulesets.locations[0]=classpath:rulesets/etsy-2026-12b.yaml",
        "listing-guard.rulesets.locations[1]=classpath:rulesets/amazon-2026-12d.yaml"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ContentRulesetsCheckIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private JsonNode check(String marketplace, String listing) throws Exception {
        String body = "{\"marketplace\":\"" + marketplace + "\",\"listing\":" + listing + "}";
        return mapper.readTree(mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> ruleIds(JsonNode result) {
        List<String> ids = new ArrayList<>();
        result.get("findings").forEach(finding -> ids.add(finding.get("ruleId").asText()));
        return ids;
    }

    private static String etsy(String title, String description) {
        return """
                {"title": "%s", "description": "%s", "tags": ["mug", "ceramic"], "price": {"amount": 19.99, "currency_code": "USD"},
                 "category": "Home > Mugs", "materials": ["clay"], "images": ["https://example.com/1.jpg"], "quantity": 5,
                 "who_made": "i_did", "when_made": "made_to_order"}
                """.formatted(title, description);
    }

    @Test
    void aCleanListingHasNoFindingAndNamesTheNewVersion() throws Exception {
        JsonNode result = check("ETSY", etsy("Handmade ceramic mug " + UUID.randomUUID(), "A nice mug for tea."));

        assertThat(result.get("rulesetVersion").asText()).isEqualTo("etsy-2026-12b");
        assertThat(ruleIds(result)).isEmpty();
    }

    @Test
    void aListingThatBreaksTheContentRulesGetsTheirFindingsWithoutAnyPosition() throws Exception {
        JsonNode result = check("ETSY", etsy("[NAME] Mug 11oz for Mom " + UUID.randomUUID(), "This 15 oz mug is a gift for dad. Café TBD"));

        assertThat(ruleIds(result)).contains("placeholder_leftover", "size_unit_conflict", "recipient_conflict").doesNotContain("clone_leftover_from_source");
        result.get("findings").forEach(finding -> {
            assertThat(finding.has("matchStart")).isFalse();
            assertThat(finding.has("matchEnd")).isFalse();
            assertThat(finding.get("severity").asText()).isIn("WARNING", "INFO", "ERROR");
        });
        assertThat(result.get("findings").findValuesAsText("ruleId")).doesNotContain("clone_leftover_from_source");
    }

    @Test
    void anAmazonListingIsCheckedByTheNewAmazonVersion() throws Exception {
        String listing = """
                {"title": "Funny Mug Pack of 2 %s", "brand": "Roadster", "bullet_points": ["Includes 4 pieces"], "description": "A mug.",
                 "search_terms": "mug", "product_type": "SHIRT", "price": {"amount": 49.99, "currency_code": "USD"}, "quantity": 5,
                 "images": ["https://example.com/1.jpg"]}
                """.formatted(UUID.randomUUID());

        JsonNode result = check("AMAZON", listing);

        assertThat(result.get("rulesetVersion").asText()).isEqualTo("amazon-2026-12d");
        assertThat(ruleIds(result)).contains("quantity_conflict", "product_noun_conflict");
    }
}
