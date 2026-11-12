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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

    /**
     * The upgrade to Spring Boot 4 (ADR-020) must not change {@code docs/api/openapi.json}. Spring Framework 7 marks the members of
     * {@code ProblemDetail} with JSpecify {@code @Nullable}, which springdoc 3.2.0 does not read, so a customizer
     * ({@code OpenApiConfig.nullableProblemDetailMembers}) keeps them nullable. This pins the published shape, in the committed file and
     * in what the code generates, and fails with a message that names the cause if the customizer stops working.
     */
    @Test
    void theProblemDetailSchemaKeepsItsPublishedNullableMembers() throws Exception {
        JsonNode committed = mapper.readTree(Files.readString(SPEC, StandardCharsets.UTF_8)).at("/components/schemas/ProblemDetail/properties");
        JsonNode generated = mapper.readTree(generatedSpec()).at("/components/schemas/ProblemDetail/properties");

        for (JsonNode properties : List.of(committed, generated)) {
            assertThat(fieldNames(properties)).containsExactlyInAnyOrder("detail", "instance", "properties", "status", "title", "type");
            assertThat(typesOf(properties.get("title"))).containsExactly("string", "null");
            assertThat(typesOf(properties.get("detail"))).containsExactly("string", "null");
            assertThat(typesOf(properties.get("instance"))).containsExactly("string", "null");
            assertThat(typesOf(properties.get("properties"))).containsExactly("object", "null");
            assertThat(typesOf(properties.get("status"))).as("not nullable").containsExactly("integer");
            assertThat(typesOf(properties.get("type"))).as("not nullable").containsExactly("string");
        }
        assertThat(generated).as("the generated ProblemDetail schema is the committed one").isEqualTo(committed);
    }

    @Test
    void theSpecDescribesTheV1ApiWithKeyAuthAndExamples() throws Exception {
        JsonNode spec = mapper.readTree(generatedSpec());

        assertThat(fieldNames(spec.at("/paths"))).containsExactlyInAnyOrder("/api/v1/checks", "/api/v1/checks/{id}",
                "/api/v1/trademark-checks", "/api/v1/trademark-checks/{id}", "/api/v1/usage",
                "/api/v1/variation-checks", "/api/v1/variation-checks/{id}",
                "/api/v1/personalization-screenings", "/api/v1/personalization-screenings/{id}",
                "/api/v1/image-checks", "/api/v1/image-checks/{id}",
                "/api/v1/rulesets/{marketplace}/limits", "/api/v1/reports/summary", "/api/v1/capabilities", "/health");
        // capabilities (ADR-017): read only, no plan pack, no quota, no 404 (it needs no marketplace)
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1capabilities"))).as("read only: no other method")
                .containsExactly("get");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1capabilities/get/responses")))
                .containsExactlyInAnyOrder("200", "304", "401", "429");
        assertThat(fieldNames(spec.at("/components/schemas/Capabilities/properties")))
                .containsExactlyInAnyOrder("packs", "trademarkCatalog", "plans", "fixes");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1rulesets~1{marketplace}~1limits/get/responses")))
                .containsExactlyInAnyOrder("200", "304", "401", "404", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1rulesets~1{marketplace}~1limits")))
                .as("read only: no other method").containsExactly("get");
        assertThat(fieldNames(spec.at("/components/schemas/LimitView/properties")))
                .containsExactlyInAnyOrder("field", "ruleId", "kind", "value", "unit", "severity", "sourceStatus", "enabled");
        assertThat(spec.at("/components/securitySchemes/ApiKeyAuth/name").asText()).isEqualTo("X-API-Key");
        assertThat(spec.at("/security/0/ApiKeyAuth").isArray()).isTrue();
        assertThat(spec.at("/paths/~1health/get/security").isEmpty()).as("/health needs no key").isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1checks/post/requestBody/content/application~1json/examples/etsy-listing").isMissingNode())
                .isFalse();
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1checks/post/responses"))).contains("200", "400", "401", "403", "413", "422", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1trademark-checks/post/responses")))
                .contains("200", "400", "401", "403", "413", "422", "429", "503");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1usage/get/responses"))).containsExactlyInAnyOrder("200", "401", "429");
        // listing health report (ADR-018): read only, no plan pack and no quota (so no 403 for a plan), its own operation id
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1reports~1summary"))).as("read only: no other method").containsExactly("get");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1reports~1summary/get/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "422", "429");
        assertThat(spec.at("/paths/~1api~1v1~1reports~1summary/get/operationId").asText()).isEqualTo("getReportSummary");
        List<String> reportParameters = new ArrayList<>();
        spec.at("/paths/~1api~1v1~1reports~1summary/get/parameters")
                .forEach(parameter -> reportParameters.add(parameter.get("name").asText() + ":" + parameter.get("required").asBoolean()));
        assertThat(reportParameters).containsExactly("from:true", "to:true", "marketplace:false");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1reports~1summary/get/responses/400/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("invalid-report-period", "report-period-too-long", "unknown-marketplace");
        assertThat(fieldNames(spec.at("/components/schemas/HealthReport/properties"))).containsExactlyInAnyOrder("period", "marketplace",
                "generatedAt", "versions", "packs", "byWeek", "coverageNote");
        assertThat(fieldNames(spec.at("/components/schemas/ReadinessSummaryView/properties"))).containsExactlyInAnyOrder(
                "checksWithReadiness", "scoredChecks", "incompleteChecks", "readyChecks", "readyRate", "averageScore",
                "checksWithoutReadiness", "versions");
        assertThat(spec.at("/components/schemas/CheckResult/properties/readiness/anyOf/0/$ref").asText())
                .as("the readiness of a check keeps its own schema; the report's summary has another name")
                .isEqualTo("#/components/schemas/ReadinessView");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1checks/post/responses/429/content/application~1problem+json/examples")))
                .as("both 429 answers are documented").containsExactlyInAnyOrder("rate-limited", "quota-exceeded");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1trademark-checks~1{id}/get/responses"))).contains("200", "404", "429");
        // variation checks (ADR-015): their own request and result, no 503 (the pack needs no catalog), both 429 answers
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1variation-checks/post/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "403", "413", "415", "422", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1variation-checks~1{id}/get/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "404", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1variation-checks/post/responses/429/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("rate-limited", "quota-exceeded");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1variation-checks/post/requestBody/content/application~1json/examples")))
                .containsExactlyInAnyOrder("amazon-family", "etsy-family");
        assertThat(spec.at("/paths/~1api~1v1~1variation-checks/post/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/VariationCheckRequest");
        assertThat(fieldNames(spec.at("/components/schemas/VariationCheckRequest/properties")))
                .as("not the /checks envelope").containsExactlyInAnyOrder("marketplace", "parent", "variation_properties", "children");
        assertThat(fieldNames(spec.at("/components/schemas/VariationCheckResult/properties"))).containsExactlyInAnyOrder(
                "checkId", "marketplace", "rulesetVersion", "analysisVersion", "cached", "completeness", "failedRuleIds",
                "disclaimer", "createdAt", "expiresAt", "summary", "findings");
        // personalization screening (ADR-016): its own request and result, no 503 (a missing catalog is part of the answer)
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1personalization-screenings/post/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "403", "413", "415", "422", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1personalization-screenings~1{id}/get/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "404", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1personalization-screenings/post/responses/400/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("invalid-request", "unknown-print-profile");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1personalization-screenings/post/responses/429/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("rate-limited", "quota-exceeded");
        assertThat(spec.at("/paths/~1api~1v1~1personalization-screenings/post/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/PersonalizationScreeningRequest");
        assertThat(fieldNames(spec.at("/components/schemas/PersonalizationScreeningRequest/properties")))
                .as("not the /checks envelope").containsExactlyInAnyOrder("marketplace", "product_type", "print_profile", "values");
        assertThat(fieldNames(spec.at("/components/schemas/PersonalizationScreeningResult/properties"))).containsExactlyInAnyOrder(
                "checkId", "marketplace", "rulesetVersion", "analysisVersion", "cached", "printProfile", "completeness",
                "failedRuleIds", "disclaimer", "createdAt", "expiresAt", "summary", "findings", "trademark");
        assertThat(fieldNames(spec.at("/components/schemas/TrademarkFindingView/properties")))
                .as("the buyer's text is never in an answer: no snippet, no token")
                .doesNotContain("snippet", "token", "text", "normalizedToken");
        assertThat(spec.at("/components/schemas/PersonalizationScreeningResult/properties/findings/items/$ref").asText())
                .as("its own finding schema: springdoc merges nested records of one simple name, which would give it another pack's shape")
                .isEqualTo("#/components/schemas/CharacterFindingView");
        assertThat(fieldNames(spec.at("/components/schemas/CharacterFindingView/properties"))).containsExactlyInAnyOrder(
                "ruleId", "kind", "severity", "sourceStatus", "field", "label", "start", "end", "value", "message", "suggestion");
        // image checks (ADR-008): multipart only, never JSON, its own result; 503 when off, 413 and 415 for the upload
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1image-checks/post/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "403", "413", "415", "422", "429", "503");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1image-checks~1{id}/get/responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "404", "429");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1image-checks/post/requestBody/content")))
                .as("multipart only: an image is never sent as JSON, a URL or a path").containsExactly("multipart/form-data");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1image-checks/post/responses/400/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("invalid-request", "too-many-images", "unknown-image-profile");
        assertThat(fieldNames(spec.at("/paths/~1api~1v1~1image-checks/post/responses/429/content/application~1problem+json/examples")))
                .containsExactlyInAnyOrder("rate-limited", "quota-exceeded");
        assertThat(spec.at("/paths/~1api~1v1~1image-checks/post/operationId").asText()).isEqualTo("checkImages");
        assertThat(spec.at("/paths/~1api~1v1~1image-checks~1{id}/get/operationId").asText()).isEqualTo("getImageCheck");
        assertThat(fieldNames(spec.at("/components/schemas/ImageCheckResult/properties"))).containsExactlyInAnyOrder(
                "checkId", "marketplace", "profile", "rulesetVersion", "analysisVersion", "cached", "completeness", "summary",
                "failedRules", "disclaimer", "createdAt", "expiresAt", "findings", "images");
        assertThat(fieldNames(spec.at("/components/schemas/ImageView/properties"))).as("no bytes, no file name")
                .containsExactlyInAnyOrder("ordinal", "imageHash", "role", "source", "completeness", "failedRules", "metadata", "findings");
        // the new operations name themselves, so the numbered ids of the older ones are as they were (no other endpoint changes)
        assertThat(spec.at("/paths/~1api~1v1~1personalization-screenings/post/operationId").asText()).isEqualTo("screenPersonalization");
        assertThat(spec.at("/paths/~1api~1v1~1personalization-screenings~1{id}/get/operationId").asText())
                .isEqualTo("getPersonalizationScreening");
        assertThat(spec.at("/paths/~1api~1v1~1checks/post/operationId").asText()).isEqualTo("check_2");
        assertThat(spec.at("/paths/~1api~1v1~1checks~1{id}/get/operationId").asText()).isEqualTo("get_2");
        assertThat(spec.at("/paths/~1api~1v1~1trademark-checks/post/operationId").asText()).isEqualTo("check_1");
        assertThat(spec.at("/paths/~1api~1v1~1trademark-checks~1{id}/get/operationId").asText()).isEqualTo("get_1");
        assertThat(spec.at("/paths/~1api~1v1~1variation-checks/post/operationId").asText()).isEqualTo("check");
        assertThat(spec.at("/paths/~1api~1v1~1variation-checks~1{id}/get/operationId").asText()).isEqualTo("get");
        assertThat(spec.at("/paths/~1api~1v1~1trademark-checks/post/requestBody/content/application~1json/schema/$ref").asText())
                .as("the /checks envelope plus an optional product_type, in a schema of its own")
                .isEqualTo("#/components/schemas/TrademarkCheckRequest");
        assertThat(spec.at("/components/schemas/TrademarkCheckRequest/properties/product_type").isMissingNode()).isFalse();
        assertThat(spec.at("/components/schemas/TrademarkCheckRequest/required").toString())
                .doesNotContain("product_type");
        assertThat(spec.at("/components/schemas/TrademarkCheckResult/properties/findings/items/$ref").asText())
                .as("its own finding schema: springdoc merges nested records of one simple name, which gave it the /checks finding")
                .isEqualTo("#/components/schemas/TrademarkCheckFindingView");
        assertThat(fieldNames(spec.at("/components/schemas/TrademarkCheckFindingView/properties"))).containsExactlyInAnyOrder(
                "ruleId", "risk", "field", "start", "end", "snippet", "mark", "matchType", "score", "insideColorPhrase",
                "otherRuleIds", "sourceIds", "sourceLinks", "riskAdjustments", "message", "suggestion");
        assertThat(spec.at("/components/schemas/CheckResult/properties/findings/items/$ref").asText())
                .as("/checks keeps its own finding schema").isEqualTo("#/components/schemas/FindingView");
        assertThat(spec.at("/components/schemas/CheckResult/properties/readiness/anyOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/ReadinessView");
        assertThat(spec.at("/components/schemas/CheckResult/properties/readiness/anyOf/1/type").asText())
                .isEqualTo("null");
        assertThat(spec.at("/components/schemas/FindingView/properties/fix/anyOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/FixView");
        assertThat(spec.at("/components/schemas/FindingView/properties/fix/anyOf/1/type").asText())
                .isEqualTo("null");
    }

    /** ADR-022: the contract headers are documented once and referenced from every response of every /api/v1 operation. */
    @Test
    void everyApiV1ResponseDocumentsTheContractHeaders() throws Exception {
        JsonNode spec = mapper.readTree(generatedSpec());
        List<String> headers = List.of("X-Listing-Guard-Version", "Deprecation", "Sunset");

        assertThat(fieldNames(spec.at("/components/headers"))).containsExactlyInAnyOrderElementsOf(headers);
        assertThat(spec.at("/components/headers/X-Listing-Guard-Version/required").asBoolean()).as("always there").isTrue();
        assertThat(spec.at("/components/headers/Deprecation/required").asBoolean()).as("only when deprecated").isFalse();
        int responses = 0;
        for (String path : fieldNames(spec.at("/paths"))) {
            JsonNode item = spec.get("paths").get(path);
            for (String method : fieldNames(item)) {
                JsonNode operationResponses = item.get(method).get("responses");
                for (String status : fieldNames(operationResponses)) {
                    JsonNode documented = operationResponses.get(status).path("headers");
                    if (path.startsWith("/api/v1/")) {
                        responses++;
                        for (String header : headers) {
                            assertThat(documented.at("/" + header + "/$ref").asText()).as(method + " " + path + " " + status)
                                    .isEqualTo("#/components/headers/" + header);
                        }
                    } else {
                        assertThat(documented.isMissingNode()).as(method + " " + path + " has no contract header").isTrue();
                    }
                }
            }
        }
        assertThat(responses).isPositive();
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

    @Test
    void theAmazonExampleIsWhatTheEndpointReallyAnswers() throws Exception {
        JsonNode actual = mapper.readTree(mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.AMAZON_REQUEST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode example = mapper.readTree(ApiExamples.AMAZON_RESULT);

        assertThat(actual.get("findings")).isEqualTo(example.get("findings"));
        assertThat(actual.get("summary")).isEqualTo(example.get("summary"));
        assertThat(actual.get("marketplace")).isEqualTo(example.get("marketplace"));
        assertThat(actual.get("rulesetVersion")).isEqualTo(example.get("rulesetVersion"));
        assertThat(actual.get("analysisVersion")).isEqualTo(example.get("analysisVersion"));
        assertThat(fieldNames(actual)).isEqualTo(fieldNames(example));
    }

    @Test
    void theSpecCarriesTheAmazonExamples() throws Exception {
        JsonNode spec = mapper.readTree(generatedSpec());

        assertThat(spec.at("/paths/~1api~1v1~1checks/post/requestBody/content/application~1json/examples/amazon-listing").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1v1~1checks/post/responses/200/content/application~1json/examples/amazon-findings").isMissingNode())
                .isFalse();
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

    /** The {@code type} of a schema as a list: {@code "string"} is one entry, {@code ["string", "null"]} is two. */
    private static List<String> typesOf(JsonNode schema) {
        JsonNode type = schema.get("type");
        List<String> types = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(each -> types.add(each.asText()));
        } else {
            types.add(type.asText());
        }
        return types;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
