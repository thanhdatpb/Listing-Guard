package com.thanhdatpb.listingguard.api.trademark;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** No catalog is configured (the default): trademark checks answer 503 with a stable code and nothing else is affected. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TrademarkNotConfiguredIntegrationTest {

    private static final String BODY = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug\"}}";

    @Autowired
    MockMvc mvc;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void thePackIsDisabledByDefault() {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.DISABLED);
    }

    @Test
    void postAnswers503WithAStableProblemCode() throws Exception {
        mvc.perform(post("/api/v1/trademark-checks").header(HEADER, KEY_1).header("X-Request-Id", "tm-503")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("trademark-not-configured"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:trademark-not-configured"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.requestId").value("tm-503"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("catalog")));
    }

    @Test
    void theOpenApiExampleIsTheRealProblem() throws Exception {
        com.fasterxml.jackson.databind.JsonNode example = mapper.readTree(ApiExamples.PROBLEM_TRADEMARK_NOT_CONFIGURED);

        com.fasterxml.jackson.databind.JsonNode actual = mapper.readTree(mvc.perform(post("/api/v1/trademark-checks")
                        .header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString());

        for (String field : new String[]{"type", "title", "status", "detail", "instance", "code"}) {
            assertThat(actual.get(field)).as(field).isEqualTo(example.get(field));
        }
        assertThat(actual.get("requestId")).isNotNull();
    }

    @Test
    void aBadRequestIsStillABadRequest() throws Exception {
        mvc.perform(post("/api/v1/trademark-checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"NOPE\",\"listing\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown-marketplace"));
    }

    @Test
    void getOfAnUnknownIdIsA404NotA503() throws Exception {
        mvc.perform(get("/api/v1/trademark-checks/" + UUID.randomUUID()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("trademark-check-not-found"));
    }

    @Test
    void checksKeepsWorking() throws Exception {
        mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug " + UUID.randomUUID() + "\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisVersion").value("etsy-2026-11"));
    }
}
