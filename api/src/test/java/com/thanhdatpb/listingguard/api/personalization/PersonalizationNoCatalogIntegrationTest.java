package com.thanhdatpb.listingguard.api.personalization;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.body;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.emoji;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A catalog that is not served (the default: nothing configured) does not make a 503 here (ADR-016): the character rules run,
 * the trademark part is NOT_CONFIGURED with an UNKNOWN risk (never LOW), and the result is INCOMPLETE, stored for GET and
 * never reused. {@code /trademark-checks} keeps answering 503 as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PersonalizationNoCatalogIntegrationTest {

    private static final String SCREENINGS = "/api/v1/personalization-screenings";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TrademarkCatalogRuntime runtime;

    private JsonNode screen(String json) throws Exception {
        String response = mvc.perform(post(SCREENINGS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response);
    }

    @Test
    void theCharacterRulesRunAndTheTrademarkPartIsUnknownNotLow() throws Exception {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.DISABLED);

        JsonNode result = screen(MAPPER.writeValueAsString(body("ETSY", null, "name", "Max " + emoji())));

        JsonNode trademark = result.get("trademark");
        assertThat(trademark.get("status").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(trademark.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(trademark.get("catalogVersion").isNull()).isTrue();
        assertThat(trademark.get("rulesetVersion").isNull()).isTrue();
        assertThat(trademark.get("findings")).isEmpty();
        assertThat(result.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("failedRuleIds")).isEmpty();
        assertThat(result.get("findings")).hasSize(1);
        assertThat(result.get("findings").get(0).get("ruleId").asText()).isEqualTo("unsupported_characters");
        assertThat(result.get("findings").get(0).get("start").asInt()).isEqualTo(4);
    }

    @Test
    void anIncompleteResultIsStoredForGetAndNeverReused() throws Exception {
        String json = MAPPER.writeValueAsString(body("ETSY", null, "name", unique()));

        JsonNode first = screen(json);
        JsonNode second = screen(json);

        assertThat(first.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId")).isNotEqualTo(first.get("checkId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE id IN (?::uuid, ?::uuid)",
                Integer.class, first.get("checkId").asText(), second.get("checkId").asText())).isEqualTo(2);
        mvc.perform(get(SCREENINGS + "/" + first.get("checkId").asText()).header(HEADER, KEY_1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trademark.status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.trademark.riskLevel").value("UNKNOWN"));
    }

    @Test
    void theTrademarkEndpointStillAnswers503AndIsNotAffected() throws Exception {
        mvc.perform(post("/api/v1/trademark-checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"A plain title\"}}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("trademark-not-configured"));
    }

}
