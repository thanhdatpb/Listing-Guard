package com.thanhdatpb.listingguard.api.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CheckControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String ETSY_BODY = """
            {
              "marketplace": "ETSY",
              "listing": {
                "title": "Handmade Mug",
                "description": "A ceramic mug.",
                "tags": ["mug", "ceramic"],
                "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                "materials": ["clay"],
                "images": ["https://example.com/1.jpg"]
              }
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ListingCheckRepository checks;

    @Test
    void healthReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void postChecksPersistsCheckAndReturnsNoFindings() throws Exception {
        String response = mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON).content(ETSY_BODY))
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
    }

    @Test
    void sameListingGetsSameContentHashButSeparateChecks() throws Exception {
        JsonNode first = postEtsy(ETSY_BODY);
        JsonNode second = postEtsy(ETSY_BODY);

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

    private JsonNode postEtsy(String body) throws Exception {
        String response = mvc.perform(post("/checks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
