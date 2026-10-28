package com.thanhdatpb.listingguard.api;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The cross-cutting contract of the API as a client sees it: authentication, request ids, problems, limits. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiContractIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    private static void assertProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:" + code))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---- health ---------------------------------------------------------------------------------------

    @Test
    void healthNeedsNoKeyAndReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("X-Request-Id"));
    }

    // ---- authentication -------------------------------------------------------------------------------

    @Test
    void everythingExceptHealthNeedsAnApiKey() throws Exception {
        assertProblem(mvc.perform(get(CHECKS + "/" + UUID.randomUUID())), 401, "unauthorized");
        assertProblem(mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, "w".repeat(40))), 401, "unauthorized");
        assertProblem(mvc.perform(post(CHECKS).contentType(MediaType.APPLICATION_JSON).content("{}")), 401, "unauthorized");
        assertProblem(mvc.perform(get("/nope")), 401, "unauthorized");
        assertProblem(mvc.perform(get("/policies/search").param("marketplace", "ETSY").param("q", "x")), 401, "unauthorized");
        mvc.perform(get(CHECKS + "/" + UUID.randomUUID())).andExpect(header().string("WWW-Authenticate", Matchers.startsWith("ApiKey")));
    }

    @Test
    void bothKeysWorkSoAKeyCanBeRotated() throws Exception {
        assertProblem(mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, KEY_1)), 404, "check-not-found");
        assertProblem(mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, KEY_2)), 404, "check-not-found");
    }

    @Test
    void aWrongKeyIsNeverEchoedBack() throws Exception {
        String wrong = "this-is-not-a-valid-key-0123456789abc";

        String body = mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, wrong))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(wrong);
    }

    // ---- request id -----------------------------------------------------------------------------------

    @Test
    void aClientRequestIdIsKeptInTheHeaderAndInProblemResponses() throws Exception {
        ResultActions result = mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, KEY_1).header("X-Request-Id", "preview-req-42"));

        assertProblem(result, 404, "check-not-found");
        result.andExpect(header().string("X-Request-Id", "preview-req-42")).andExpect(jsonPath("$.requestId").value("preview-req-42"));
    }

    @Test
    void aMissingRequestIdIsGeneratedAndMatchesTheProblem() throws Exception {
        var response = mvc.perform(get(CHECKS + "/" + UUID.randomUUID()).header(HEADER, KEY_1)).andReturn().getResponse();

        String id = response.getHeader("X-Request-Id");
        assertThat(id).hasSize(36);
        assertThat(response.getContentAsString()).contains("\"requestId\":\"" + id + "\"");
    }

    // ---- problems for protocol errors -------------------------------------------------------------------

    @Test
    void unknownRoutesWrongMethodsAndWrongMediaTypesAreProblemsToo() throws Exception {
        assertProblem(mvc.perform(get("/nope").header(HEADER, KEY_1)), 404, "not-found");
        mvc.perform(get("/nope").header(HEADER, KEY_1)).andExpect(jsonPath("$.detail").value("No endpoint matches /nope"));

        assertProblem(mvc.perform(delete(CHECKS).header(HEADER, KEY_1)), 405, "method-not-allowed");
        mvc.perform(delete(CHECKS).header(HEADER, KEY_1)).andExpect(header().exists("Allow"));

        assertProblem(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.TEXT_PLAIN).content("hi")), 415, "unsupported-media-type");
    }

    @Test
    void invalidRequestsAreProblemsWithTheFieldsThatAreWrong() throws Exception {
        assertProblem(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{")), 400, "invalid-request");
        assertProblem(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{}")), 400, "invalid-request");
        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("marketplace", "listing")));
    }

    // ---- body size ----------------------------------------------------------------------------------------

    @Test
    void aBodyOverTheLimitIs413AndOneJustUnderItIsAccepted() throws Exception {
        String underLimit = body("d".repeat(250_000));
        String overLimit = body("d".repeat(300_000));
        assertThat(underLimit.length()).isLessThan(262_144);
        assertThat(overLimit.length()).isGreaterThan(262_144);

        mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(underLimit))
                .andExpect(status().isOk());
        assertProblem(mvc.perform(post(CHECKS).header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON).content(overLimit)),
                413, "payload-too-large");
    }

    private static String body(String description) {
        return "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Body size " + UUID.randomUUID() + "\",\"description\":\"" + description + "\"}}";
    }
}
