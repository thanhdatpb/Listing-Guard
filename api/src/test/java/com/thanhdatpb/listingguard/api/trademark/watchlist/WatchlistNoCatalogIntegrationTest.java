package com.thanhdatpb.listingguard.api.trademark.watchlist;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A deployment that serves no catalog (ADR-031): a client with a watchlist still gets an answer, INCOMPLETE and marked
 * NOT_CONFIGURED, with the watchlist findings, never cached; a client without one gets the 503 it always got.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WatchlistNoCatalogIntegrationTest {

    private static final String TRADEMARK = "/api/v1/trademark-checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    TrademarkCatalogRuntime runtime;

    private String key;
    private String mark;

    @BeforeEach
    void client() {
        String name = "wln-" + WatchlistApiIntegrationTest.unique().substring(0, 20);
        provisioning.createClient(name);
        key = provisioning.createKey(name, "watchlist test").secret();
        mark = "Brimwick Otters " + WatchlistApiIntegrationTest.unique();
    }

    private ResultActions check(String title) throws Exception {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.set("listing", listing);
        return mvc.perform(post(TRADEMARK).header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void watch(String markText) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("entries").addObject().put("mark", markText).put("note", "từng bị claim");
        mvc.perform(put("/api/v1/trademark-watchlist").header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
    }

    @Test
    void theCatalogIsNotServed() {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.DISABLED);
    }

    @Test
    void withoutAWatchlistTheCheckIsRefusedAsBefore() throws Exception {
        JsonNode problem = json(check("Retro " + mark + " tee").andExpect(status().isServiceUnavailable()));

        assertThat(problem.get("code").asText()).isEqualTo("trademark-not-configured");
    }

    @Test
    void withAWatchlistTheCheckAnswersFromItAndSaysItIsIncomplete() throws Exception {
        watch(mark);

        JsonNode found = json(check("Retro " + mark + " tee").andExpect(status().isOk()));
        JsonNode again = json(check("Retro " + mark + " tee").andExpect(status().isOk()));
        JsonNode nothing = json(check("Plain cotton tee").andExpect(status().isOk()));

        assertThat(found.get("catalogStatus").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(found.get("catalogVersion").asText()).isEqualTo("none");
        assertThat(found.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(found.get("failedRuleIds")).isEmpty();
        assertThat(found.get("skippedRuleIds").toString()).contains("catalog_exact", "catalog_phrase", "catalog_fuzzy")
                .doesNotContain("geo_sport_combination");
        assertThat(found.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(found.get("findings")).singleElement().satisfies(finding -> {
            assertThat(finding.get("source").asText()).isEqualTo("WATCHLIST");
            assertThat(finding.get("watchlistEntry").get("mark").asText()).isEqualTo(mark);
        });
        assertThat(again.get("cached").asBoolean()).isFalse();
        assertThat(again.get("checkId")).isNotEqualTo(found.get("checkId"));
        assertThat(nothing.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(nothing.get("findings")).isEmpty();

        JsonNode read = json(mvc.perform(get(TRADEMARK + "/" + found.get("checkId").asText()).header(HEADER, key))
                .andExpect(status().isOk()));
        assertThat(read.get("catalogStatus").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(read.get("catalogVersion").asText()).isEqualTo("none");
        assertThat(read.get("skippedRuleIds")).isEqualTo(found.get("skippedRuleIds"));
        assertThat(read.get("findings")).isEqualTo(found.get("findings"));
    }
}
