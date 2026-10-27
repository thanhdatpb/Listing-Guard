package com.thanhdatpb.listingguard.api.check;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CheckControllerIntegrationTest {

    /** A listing that satisfies every Etsy rule. */
    private static final String VALID_BODY = """
            {
              "marketplace": "ETSY",
              "listing": {
                "title": "Handmade Mug",
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
            """;

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

    @Test
    void healthReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void validListingIsPersistedWithNoFindings() throws Exception {
        String response = mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketplace").value("ETSY"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.findings").isEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(response);
        UUID checkId = UUID.fromString(json.get("checkId").asText());
        assertThat(json.get("contentHash").asText()).matches("[0-9a-f]{64}");

        ListingCheck saved = checks.findById(checkId).orElseThrow();
        assertThat(saved.getMarketplaceCode()).isEqualTo("ETSY");
        assertThat(saved.getStatus()).isEqualTo(CheckStatus.COMPLETED);
        assertThat(saved.getContentHash()).isEqualTo(json.get("contentHash").asText());
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(findings.findByCheckId(checkId)).isEmpty();
    }

    @Test
    void violatingListingReturnsAndPersistsFindings() throws Exception {
        String response = mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON).content(violatingBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.findings[?(@.ruleId=='title_length')].severity").value("ERROR"))
                .andExpect(jsonPath("$.findings[?(@.ruleId=='duplicate_tags')].severity").value("WARNING"))
                .andExpect(jsonPath("$.findings[?(@.ruleId=='banned_words')].severity").value("WARNING"))
                .andExpect(jsonPath("$.findings[?(@.ruleId=='required_fields')].field").value("quantity"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(response);
        List<String> ruleIds = new ArrayList<>();
        json.get("findings").forEach(finding -> {
            ruleIds.add(finding.get("ruleId").asText());
            assertThat(finding.get("rulesetVersion").asText()).isEqualTo("etsy-2026-11");
            assertThat(finding.get("message").asText()).isNotBlank();
            assertThat(finding.get("suggestion").asText()).isNotBlank();
        });
        assertThat(ruleIds).containsExactlyInAnyOrder("required_fields", "title_length", "tag_count", "tag_length",
                "tag_characters", "duplicate_tags", "banned_words");

        UUID checkId = UUID.fromString(json.get("checkId").asText());
        long rulesetVersionId = rulesets.get("ETSY").rulesetVersionId();
        List<FindingEntity> saved = findings.findByCheckId(checkId);
        assertThat(saved).hasSize(ruleIds.size());
        assertThat(saved).allSatisfy(entity -> {
            assertThat(entity.getSource()).isEqualTo(FindingSource.RULE);
            assertThat(entity.getRulesetVersionId()).isEqualTo(rulesetVersionId);
            assertThat(entity.getMessage()).isNotBlank();
        });
        assertThat(saved).extracting(FindingEntity::getRuleId).containsExactlyInAnyOrderElementsOf(ruleIds);
        assertThat(saved).filteredOn(entity -> entity.getRuleId().equals("title_length"))
                .extracting(FindingEntity::getSeverity).containsExactly(Severity.ERROR);
    }

    @Test
    void sameListingGetsSameContentHashButSeparateChecks() throws Exception {
        JsonNode first = postOk(VALID_BODY);
        JsonNode second = postOk(VALID_BODY);

        assertThat(second.get("contentHash").asText()).isEqualTo(first.get("contentHash").asText());
        assertThat(second.get("checkId").asText()).isNotEqualTo(first.get("checkId").asText());
    }

    @Test
    void unsupportedMarketplaceIsBadRequest() throws Exception {
        mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"NOPE\",\"listing\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingListingIsBadRequest() throws Exception {
        mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonObjectListingIsBadRequest() throws Exception {
        mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /** Breaks every Etsy rule once. Limits are read from the active ruleset, not hard-coded. */
    private String violatingBody() {
        RulesetConfig config = rulesets.get("ETSY").config();
        int maxTags = config.rule("tag_count").intParam("max_count");
        int maxTagLength = config.rule("tag_length").intParam("max_length");
        int maxTitle = config.rule("title_length").intParam("max_length");
        String bannedWord = config.rule("banned_words").stringListParam("words").get(0);

        ObjectNode listing = (ObjectNode) readTree(VALID_BODY).get("listing");
        listing.put("title", "t".repeat(maxTitle + 1));
        listing.put("description", "This item is a " + bannedWord + ".");
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

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode postOk(String body) throws Exception {
        String response = mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
