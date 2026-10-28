package com.thanhdatpb.listingguard.api;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.docs.ApiExamples;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI document: it is protected by the API key, the committed copy in docs/api/openapi.json matches what the
 * code generates, and the Etsy examples in it are what the real endpoint answers.
 * <p>
 * To refresh the committed copy after an intended API change run the build with {@code -Dopenapi.update=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpenApiIntegrationTest {

    private static final Path SPEC = Path.of("..", "docs", "api", "openapi.json");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Autowired
    MockMvc mvc;

    @Test
    void theSpecNeedsAnApiKey() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").header(HEADER, KEY_1)).andExpect(status().isOk());
    }

    @Test
    void swaggerUiIsOffByDefault() throws Exception {
        mvc.perform(get("/swagger-ui/index.html").header(HEADER, KEY_1)).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html").header(HEADER, KEY_1)).andExpect(status().isNotFound());
    }

    @Test
    void theCommittedSpecMatchesTheCode() throws Exception {
        String generated = generatedSpec();

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SPEC.getParent());
            Files.writeString(SPEC, generated, StandardCharsets.UTF_8);
        }

        assertThat(SPEC).as("docs/api/openapi.json is missing; run the build with -Dopenapi.update=true").exists();
        assertThat(Files.readString(SPEC, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("docs/api/openapi.json is out of date; run the build with -Dopenapi.update=true and commit it")
                .isEqualTo(generated);
    }

    @Test
    void theSpecDescribesTheV1ApiWithKeyAuthAndExamples() throws Exception {
        JsonNode spec = mapper.readTree(generatedSpec());

        assertThat(fieldNames(spec.at("/paths"))).containsExactlyInAnyOrder("/api/v1/checks", "/api/v1/checks/{id}", "/health");
        assertThat(spec.at("/components/securitySchemes/ApiKeyAuth/name").asText()).isEqualTo("X-API-Key");
        assertThat(spec.at("/security/0/ApiKeyAuth").isArray()).isTrue();
        assertThat(spec.at("/paths/~1health/get/security").isEmpty()).as("/health needs no key").isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1checks/post/requestBody/content/application~1json/examples/etsy-listing").isMissingNode())
                .isFalse();
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1checks/post/responses"))).contains("200", "400", "401", "413", "422", "429");
    }

    @Test
    void theEtsyExampleIsWhatTheEndpointReallyAnswers() throws Exception {
        JsonNode actual = mapper.readTree(mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.ETSY_REQUEST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode example = mapper.readTree(ApiExamples.ETSY_RESULT);

        assertThat(actual.get("findings")).isEqualTo(example.get("findings"));
        assertThat(actual.get("summary")).isEqualTo(example.get("summary"));
        assertThat(actual.get("marketplace")).isEqualTo(example.get("marketplace"));
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(example));
    }

    /** The spec as stable, pretty JSON: keys sorted, and without the server URL, which depends on where it was read. */
    private String generatedSpec() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").header(HEADER, KEY_1)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectNode spec = (ObjectNode) mapper.readTree(body);
        spec.remove("servers");
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.treeToValue(spec, Object.class))
                .replace("\r\n", "\n") + "\n";
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
