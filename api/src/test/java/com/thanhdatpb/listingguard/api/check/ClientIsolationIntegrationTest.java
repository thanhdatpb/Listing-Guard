package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1_SECOND;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Checks and the result cache belong to the client that asked (ADR-007). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ClientIsolationIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private static String body(String title) {
        return """
                {"marketplace": "ETSY", "listing": {"title": "%s", "tags": ["mug"], "quantity": 1}}
                """.formatted(title);
    }

    private JsonNode checkAs(String key, String body) throws Exception {
        String response = mvc.perform(post(CHECKS).header(HEADER, key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    /** The problem body with the parts that legitimately differ per request (ids, instance) taken out. */
    private static String shape(String problem, String id) {
        return problem.replace(id, "<id>").replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }

    @Test
    void aCheckOfAnotherClientAnswersLikeAnIdThatDoesNotExist() throws Exception {
        String id = checkAs(KEY_1, body("Isolated " + UUID.randomUUID())).get("checkId").asText();

        mvc.perform(get(CHECKS + "/" + id).header(HEADER, KEY_1)).andExpect(status().isOk());
        String foreign = mvc.perform(get(CHECKS + "/" + id).header(HEADER, KEY_2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("check-not-found"))
                .andReturn().getResponse().getContentAsString();

        String unknownId = UUID.randomUUID().toString();
        String unknown = mvc.perform(get(CHECKS + "/" + unknownId).header(HEADER, KEY_2))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(shape(foreign, id)).isEqualTo(shape(unknown, unknownId));
    }

    @Test
    void everyKeyOfTheOwningClientCanReadItsChecks() throws Exception {
        String id = checkAs(KEY_1, body("Rotation " + UUID.randomUUID())).get("checkId").asText();

        mvc.perform(get(CHECKS + "/" + id).header(HEADER, KEY_1_SECOND)).andExpect(status().isOk());
    }

    @Test
    void theSameListingFromTwoClientsIsTwoSeparateChecksEachWithItsOwnCache() throws Exception {
        String body = body("Shared content " + UUID.randomUUID());

        JsonNode firstOfA = checkAs(KEY_1, body);
        JsonNode firstOfB = checkAs(KEY_2, body);
        JsonNode secondOfA = checkAs(KEY_1_SECOND, body);
        JsonNode secondOfB = checkAs(KEY_2, body);

        assertThat(firstOfA.get("cached").asBoolean()).as("client A, first time").isFalse();
        assertThat(firstOfB.get("cached").asBoolean()).as("client B, first time: A's result is not reused").isFalse();
        assertThat(firstOfB.get("checkId").asText()).isNotEqualTo(firstOfA.get("checkId").asText());
        assertThat(secondOfA.get("cached").asBoolean()).isTrue();
        assertThat(secondOfA.get("checkId").asText()).isEqualTo(firstOfA.get("checkId").asText());
        assertThat(secondOfB.get("cached").asBoolean()).isTrue();
        assertThat(secondOfB.get("checkId").asText()).isEqualTo(firstOfB.get("checkId").asText());
        assertThat(firstOfA.get("contentHash").asText()).isEqualTo(firstOfB.get("contentHash").asText());
    }
}
