package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.mcp.ApiStub.Reply;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class ToolRunnerTest {

    private static final ObjectMapper M = ToolResults.newMapper();
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ToolCatalog catalog = new ToolCatalog(M);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private ToolSpec tool(String name) {
        return catalog.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private CallToolResult run(ApiStub stub, String tool, Map<String, Object> args) {
        return run(stub.config(), tool, args);
    }

    private CallToolResult run(McpServerConfig config, String tool, Map<String, Object> args) {
        ToolRunner runner = new ToolRunner(new ListingGuardApiClient(config), new ToolResults(M), M);
        return runner.run(tool(tool), args);
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().get(0)).text();
    }

    private static JsonNode structured(CallToolResult result) {
        return M.valueToTree(result.structuredContent());
    }

    private static String everything(CallToolResult result) {
        return text(result) + " " + structured(result);
    }

    private String allLogs() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            out.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return out.toString();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static String problem(int status, String code, String extra) {
        return "{\"type\":\"urn:listing-guard:problem:" + code + "\",\"code\":\"" + code + "\",\"title\":\"A title\","
                + "\"status\":" + status + ",\"detail\":\"A detail.\",\"requestId\":\"api-req-1\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    // ---- the tools ----------------------------------------------------------------------------------------------

    @Test
    void thereAreExactlySixToolsWithTheFixedNames() {
        assertThat(catalog.tools()).extracting(ToolSpec::name).containsExactly(
                "check_listing", "check_trademark", "check_variations", "screen_personalization", "get_limits",
                "get_usage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolHasADescriptionAndAnObjectSchemaWithConsistentRequiredNames() {
        for (ToolSpec tool : catalog.tools()) {
            assertThat(tool.description()).as(tool.name()).isNotBlank().hasSizeLessThan(900);
            Map<String, Object> schema = tool.inputSchema();
            assertThat(schema.get("type")).as(tool.name()).isEqualTo("object");
            assertThat(schema.get("additionalProperties")).as(tool.name()).isEqualTo(false);
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).as(tool.name()).isNotNull();
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            assertThat(properties.keySet()).as(tool.name()).containsAll(required);
            for (Object property : properties.values()) {
                assertThat(((Map<String, Object>) property).get("type")).isIn("string", "object", "array");
            }
        }
    }

    @Test
    void onlyTheTwoReadersAreReadOnlyAndNoToolIsNamedLikeAWrite() {
        assertThat(catalog.tools().stream().filter(ToolSpec::readOnly).map(ToolSpec::name))
                .containsExactlyInAnyOrder("get_limits", "get_usage");
        for (ToolSpec tool : catalog.tools()) {
            assertThat(tool.name()).doesNotContainIgnoringCase("apply").doesNotContainIgnoringCase("publish")
                    .doesNotContainIgnoringCase("edit").doesNotContainIgnoringCase("fix")
                    .doesNotContainIgnoringCase("update").doesNotContainIgnoringCase("delete")
                    .doesNotContainIgnoringCase("save").doesNotContainIgnoringCase("set_");
        }
    }

    static Stream<Arguments> calls() {
        return Stream.of(
                Arguments.of("check_listing",
                        map("marketplace", "ETSY", "listing", map("title", "Soft jersey", "tags", List.of("a", "b"))),
                        "POST", "/api/v1/checks"),
                Arguments.of("check_trademark",
                        map("marketplace", "AMAZON", "listing", map("title", "Soft jersey", "brand", "Zorvex")),
                        "POST", "/api/v1/trademark-checks"),
                Arguments.of("check_variations",
                        map("marketplace", "AMAZON", "parent", map("sku", "P"),
                                "children", List.of(map("sku", "C1", "attributes", map("color", "Red")))),
                        "POST", "/api/v1/variation-checks"),
                Arguments.of("screen_personalization",
                        map("marketplace", "ETSY", "print_profile", "latin_basic", "product_type", "jersey",
                                "values", List.of(map("label", "name", "text", "Đạt"))),
                        "POST", "/api/v1/personalization-screenings"),
                Arguments.of("get_limits", map("marketplace", "ETSY", "product_type", "SHIRT"),
                        "GET", "/api/v1/rulesets/ETSY/limits?productType=SHIRT"),
                Arguments.of("get_limits", map("marketplace", "AMAZON"),
                        "GET", "/api/v1/rulesets/AMAZON/limits"),
                Arguments.of("get_usage", map(), "GET", "/api/v1/usage"));
    }

    @ParameterizedTest(name = "{0} calls {2} {3}")
    @MethodSource("calls")
    void eachToolCallsTheRightEndpointWithTheMethodTheKeyAndTheBodyAsGiven(String tool, Map<String, Object> args,
            String method, String target) throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{\"ok\":true}"))) {
            CallToolResult result = run(stub, tool, args);

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.method()).isEqualTo(method);
            assertThat(recorded.target()).isEqualTo(target);
            assertThat(recorded.header("X-API-Key")).isEqualTo(ApiStub.KEY);
            assertThat(recorded.header("X-Request-Id")).matches(UUID);
            if (method.equals("POST")) {
                assertThat(M.readTree(recorded.body())).isEqualTo(M.valueToTree(args));
            } else {
                assertThat(recorded.body()).isEmpty();
            }
            assertThat(result.isError()).isFalse();
        }
    }

    @Test
    void theBuyersTextIsSentExactlyAsTyped() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            run(stub, "screen_personalization", map("marketplace", "ETSY",
                    "values", List.of(map("label", "name", "text", "  Đạt  "), map("text", "a  b\t"))));

            JsonNode body = M.readTree(stub.only().body());
            assertThat(body.at("/values/0/text").asText()).isEqualTo("  Đạt  ");
            assertThat(body.at("/values/1/text").asText()).isEqualTo("a  b\t");
        }
    }

    @Test
    void anOptionalArgumentSentAsNullIsLeftOut() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            CallToolResult result = run(stub, "get_limits", map("marketplace", "ETSY", "product_type", null));

            assertThat(result.isError()).isFalse();
            assertThat(stub.only().target()).isEqualTo("/api/v1/rulesets/ETSY/limits");
        }
    }

    // ---- results ------------------------------------------------------------------------------------------------

    @Test
    void anAnswerIsReturnedVerbatimIncludingIncompleteAndUnknown() throws Exception {
        String body = "{\"checkId\":\"0b9f\",\"marketplace\":\"ETSY\",\"rulesetVersion\":\"trademark-risk-2026-11\","
                + "\"catalogVersion\":\"demo-2026-11-0123\",\"analysisVersion\":\"9f2c\",\"cached\":false,"
                + "\"completeness\":\"INCOMPLETE\",\"riskLevel\":\"UNKNOWN\",\"failedRuleIds\":[\"catalog_fuzzy\"],"
                + "\"disclaimer\":\"Kết quả chỉ là cảnh báo tham khảo.\",\"findings\":["
                + "{\"ruleId\":\"catalog_exact\",\"score\":1.0,\"price\":2999.50,\"mark\":\"ZORVEX\"}]}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
            CallToolResult result = run(stub, "check_trademark",
                    map("marketplace", "ETSY", "listing", map("title", "Zorvex shirt")));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).isEqualTo(body);
            assertThat(result.structuredContent()).isEqualTo(M.readTree(body));
            JsonNode s = structured(result);
            assertThat(s.get("completeness").asText()).isEqualTo("INCOMPLETE");
            assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
            assertThat(s.get("disclaimer").asText()).isEqualTo("Kết quả chỉ là cảnh báo tham khảo.");
            assertThat(s.at("/findings/0/score").decimalValue()).hasToString("1.0");
            assertThat(s.at("/findings/0/price").decimalValue()).hasToString("2999.50");
        }
    }

    // ---- errors of the API --------------------------------------------------------------------------------------

    @Test
    void unauthorizedIsAToolErrorWithCodeStatusAndRequestId() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(401, problem(401, "unauthorized", "")))) {
            CallToolResult result = run(stub, "get_usage", map());

            assertThat(result.isError()).isTrue();
            JsonNode s = structured(result);
            assertThat(s.get("code").asText()).isEqualTo("unauthorized");
            assertThat(s.get("status").asInt()).isEqualTo(401);
            assertThat(s.get("requestId").asText()).isEqualTo("api-req-1");
            assertThat(s.get("retryable").asBoolean()).isFalse();
            assertThat(s.get("hint").asText()).contains(McpServerConfig.API_KEY);
            assertThat(text(result)).contains("unauthorized").contains("401").contains("api-req-1");
        }
    }

    @Test
    void aPlanThatLacksThePackIsNotRetryableAndCarriesPlanAndPack() throws Exception {
        String extra = "\"plan\":\"FREE\",\"pack\":\"trademark-risk\"";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(403, problem(403, "plan-not-allowed", extra)))) {
            CallToolResult result = run(stub, "check_trademark",
                    map("marketplace", "ETSY", "listing", map("title", "x")));

            assertThat(result.isError()).isTrue();
            JsonNode s = structured(result);
            assertThat(s.get("code").asText()).isEqualTo("plan-not-allowed");
            assertThat(s.get("status").asInt()).isEqualTo(403);
            assertThat(s.get("plan").asText()).isEqualTo("FREE");
            assertThat(s.get("pack").asText()).isEqualTo("trademark-risk");
            assertThat(s.get("retryable").asBoolean()).isFalse();
        }
    }

    @Test
    void aRateLimitIsRetryableAfterTheSecondsTheApiGave() throws Exception {
        try (ApiStub stub = new ApiStub().replying(
                Reply.problem(429, problem(429, "rate-limited", "")).with("Retry-After", "7"))) {
            CallToolResult result = run(stub, "get_usage", map());

            JsonNode s = structured(result);
            assertThat(result.isError()).isTrue();
            assertThat(s.get("code").asText()).isEqualTo("rate-limited");
            assertThat(s.get("retryable").asBoolean()).isTrue();
            assertThat(s.get("retryAfterSeconds").asLong()).isEqualTo(7);
        }
    }

    @Test
    void aQuotaRefusalIsNotRetryableAndTheServerInventsNoWaitingTime() throws Exception {
        String extra = "\"plan\":\"SELLER\",\"quota\":1000,\"used\":1000,\"resetAt\":\"2026-12-01T00:00:00Z\"";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(429, problem(429, "quota-exceeded", extra)))) {
            CallToolResult result = run(stub, "check_listing",
                    map("marketplace", "ETSY", "listing", map("title", "x")));

            JsonNode s = structured(result);
            assertThat(result.isError()).isTrue();
            assertThat(s.get("code").asText()).isEqualTo("quota-exceeded");
            assertThat(s.get("plan").asText()).isEqualTo("SELLER");
            assertThat(s.get("quota").asInt()).isEqualTo(1000);
            assertThat(s.get("used").asInt()).isEqualTo(1000);
            assertThat(s.get("resetAt").asText()).isEqualTo("2026-12-01T00:00:00Z");
            assertThat(s.get("retryable").asBoolean()).isFalse();
            assertThat(s.has("retryAfterSeconds")).isFalse();
            assertThat(s.get("hint").asText()).contains("resetAt");
        }
    }

    @Test
    void aQuotaRefusalStaysNotRetryableEvenIfAProxyAddedRetryAfter() throws Exception {
        try (ApiStub stub = new ApiStub().replying(
                Reply.problem(429, problem(429, "quota-exceeded", "")).with("Retry-After", "30"))) {
            JsonNode s = structured(run(stub, "get_usage", map()));

            assertThat(s.get("retryable").asBoolean()).isFalse();
            assertThat(s.has("retryAfterSeconds")).isFalse();
        }
    }

    @Test
    void serviceUnavailableIsRetryable() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, problem(503, "service-unavailable", "")))) {
            CallToolResult result = run(stub, "check_listing", map("marketplace", "ETSY", "listing", map()));

            JsonNode s = structured(result);
            assertThat(result.isError()).isTrue();
            assertThat(s.get("code").asText()).isEqualTo("service-unavailable");
            assertThat(s.get("retryable").asBoolean()).isTrue();
        }
    }

    @Test
    void otherClientErrorsKeepTheirCodeAndAreNotRetryable() throws Exception {
        String known = "\"known\":[\"latin_basic\",\"latin_extended\"]";
        String errors = "\"errors\":[{\"field\":\"marketplace\",\"message\":\"must not be blank\",\"rejected\":\"x\"}]";
        record Case(int status, String code, String extra, String tool, Map<String, Object> args) {
        }
        List<Case> cases = List.of(
                new Case(400, "unknown-marketplace", "", "check_listing", map("marketplace", "EBAY", "listing", map())),
                new Case(400, "unknown-print-profile", known, "screen_personalization",
                        map("marketplace", "ETSY", "print_profile", "nope", "values", List.of(map("text", "A")))),
                new Case(400, "invalid-request", errors, "check_listing", map("marketplace", "ETSY", "listing", map())),
                new Case(404, "ruleset-not-found", "", "get_limits", map("marketplace", "EBAY")),
                new Case(413, "payload-too-large", "", "check_listing", map("marketplace", "ETSY", "listing", map())),
                new Case(415, "unsupported-media-type", "", "get_usage", map()));
        for (Case c : cases) {
            try (ApiStub stub = new ApiStub().replying(Reply.problem(c.status(), problem(c.status(), c.code(), c.extra())))) {
                CallToolResult result = run(stub, c.tool(), c.args());

                JsonNode s = structured(result);
                assertThat(result.isError()).as(c.code()).isTrue();
                assertThat(s.get("code").asText()).as(c.code()).isEqualTo(c.code());
                assertThat(s.get("status").asInt()).isEqualTo(c.status());
                assertThat(s.get("retryable").asBoolean()).as(c.code()).isFalse();
                if (c.code().equals("unknown-print-profile")) {
                    assertThat(s.get("known")).hasSize(2);
                }
                if (c.code().equals("invalid-request")) {
                    assertThat(s.at("/errors/0/field").asText()).isEqualTo("marketplace");
                    assertThat(s.at("/errors/0/message").asText()).isEqualTo("must not be blank");
                    assertThat(s.at("/errors/0").has("rejected")).as("only field and message are copied").isFalse();
                }
            }
        }
    }

    @Test
    void aServerErrorWithAnHtmlBodyBecomesInternalErrorWithoutTheHtml() throws Exception {
        try (ApiStub stub = new ApiStub().replying(
                Reply.json(502, "<html><body><h1>Bad Gateway</h1> nginx/1.25</body></html>"))) {
            CallToolResult result = run(stub, "get_usage", map());

            JsonNode s = structured(result);
            assertThat(result.isError()).isTrue();
            assertThat(s.get("code").asText()).isEqualTo("internal-error");
            assertThat(s.get("status").asInt()).isEqualTo(502);
            assertThat(s.get("retryable").asBoolean()).isTrue();
            assertThat(everything(result)).doesNotContain("<html>").doesNotContain("nginx");
        }
    }

    @Test
    void onlyAFixedListOfMembersIsForwardedAndNoStackTrace() throws Exception {
        String extra = "\"trace\":\"at com.example.Secret.method(Secret.java:42)\",\"exception\":\"java.lang.Boom\","
                + "\"internalHost\":\"db-7.internal\",\"properties\":{\"plan\":\"PRO\",\"leak\":\"x\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(500, problem(500, "internal-error", extra)))) {
            CallToolResult result = run(stub, "get_usage", map());

            JsonNode s = structured(result);
            assertThat(s.has("trace")).isFalse();
            assertThat(s.has("exception")).isFalse();
            assertThat(s.has("internalHost")).isFalse();
            assertThat(s.has("leak")).isFalse();
            assertThat(s.get("plan").asText()).as("a member inside properties is read").isEqualTo("PRO");
            assertThat(everything(result)).doesNotContain("Secret.java").doesNotContain("db-7.internal")
                    .doesNotContain("java.lang.Boom");
        }
    }

    @Test
    void aLongDetailIsCutAndAMalformedCodeFallsBackToTheStatus() throws Exception {
        String body = "{\"code\":\"Bad Code <script>\",\"detail\":\"" + "x".repeat(5000) + "\",\"requestId\":\"bad id!\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(500, body))) {
            CallToolResult result = run(stub, "get_usage", map());

            JsonNode s = structured(result);
            assertThat(s.get("code").asText()).isEqualTo("internal-error");
            assertThat(s.get("detail").asText().length()).isLessThanOrEqualTo(ToolResults.MAX_DETAIL_CHARS + 3);
            assertThat(s.get("requestId").asText()).as("falls back to the id the server sent").matches(UUID);
        }
    }

    // ---- trademark not configured -------------------------------------------------------------------------------

    @Test
    void aMissingTrademarkCatalogIsAResultWithAnUnknownRiskNotAnError() throws Exception {
        String body = problem(503, "trademark-not-configured", "");
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, body))) {
            CallToolResult result = run(stub, "check_trademark",
                    map("marketplace", "ETSY", "listing", map("title", "x")));

            assertThat(result.isError()).isFalse();
            JsonNode s = structured(result);
            assertThat(s.get("status").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
            assertThat(s.get("scanRan").asBoolean()).isFalse();
            assertThat(s.has("findings")).as("an empty list would read as nothing found").isFalse();
            assertThat(s.at("/derivedFrom/httpStatus").asInt()).isEqualTo(503);
            assertThat(s.at("/derivedFrom/code").asText()).isEqualTo("trademark-not-configured");
            assertThat(s.at("/derivedFrom/requestId").asText()).isEqualTo("api-req-1");
            assertThat(s.get("note").asText()).contains("unknown").contains("not a result of the trademark scanner");
            assertThat(M.readTree(text(result))).isEqualTo(s);
            assertThat(everything(result)).doesNotContain("\"LOW\"");
        }
    }

    @Test
    void otherTrademarkFailuresStayErrors() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, problem(503, "service-unavailable", "")))) {
            CallToolResult result = run(stub, "check_trademark",
                    map("marketplace", "ETSY", "listing", map("title", "x")));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result).get("code").asText()).isEqualTo("service-unavailable");
        }
    }

    @Test
    void theNotConfiguredAnswerIsOnlyForTheTrademarkTool() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, problem(503, "trademark-not-configured", "")))) {
            CallToolResult result = run(stub, "check_listing", map("marketplace", "ETSY", "listing", map()));

            assertThat(result.isError()).isTrue();
            JsonNode s = structured(result);
            assertThat(s.get("code").asText()).isEqualTo("trademark-not-configured");
            assertThat(s.get("retryable").asBoolean()).isFalse();
        }
    }

    // ---- failures that are not an answer of the API -------------------------------------------------------------

    @Test
    void aClosedPortIsApiUnreachableWithTheRequestId() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        McpServerConfig config = new McpServerConfig(ApiStub.KEY, URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2), TransportKind.STDIO, InetAddress.getLoopbackAddress(), 0, false);

        CallToolResult result = run(config, "get_usage", map());

        JsonNode s = structured(result);
        assertThat(result.isError()).isTrue();
        assertThat(s.get("code").asText()).isEqualTo("api-unreachable");
        assertThat(s.get("requestId").asText()).matches(UUID);
        assertThat(s.get("retryable").asBoolean()).isTrue();
        assertThat(everything(result)).doesNotContain("Exception").doesNotContain("\tat ");
    }

    @Test
    void aSlowApiIsApiTimeout() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(3)))) {
            CallToolResult result = run(stub.config(Duration.ofMillis(300)), "get_usage", map());

            assertThat(result.isError()).isTrue();
            assertThat(structured(result).get("code").asText()).isEqualTo("api-timeout");
            assertThat(structured(result).get("retryable").asBoolean()).isTrue();
        }
    }

    @Test
    void anAnswerThatIsNotAJsonObjectIsApiInvalidResponse() throws Exception {
        for (String body : new String[] {"not json", "[1,2,3]", "\"text\"", "", "null"}) {
            try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
                CallToolResult result = run(stub, "get_usage", map());

                assertThat(result.isError()).as(body).isTrue();
                assertThat(structured(result).get("code").asText()).as(body).isEqualTo("api-invalid-response");
                assertThat(structured(result).get("retryable").asBoolean()).isFalse();
            }
        }
    }

    @Test
    void aRedirectIsApiInvalidResponseAndNamesTheSetting() throws Exception {
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(301, "").with("Location", "https://elsewhere.example/"))) {
            CallToolResult result = run(stub, "get_usage", map());

            assertThat(result.isError()).isTrue();
            assertThat(structured(result).get("code").asText()).isEqualTo("api-invalid-response");
            assertThat(text(result)).contains(McpServerConfig.BASE_URL);
            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void anOversizedAnswerIsApiInvalidResponse() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(ListingGuardApiClient.MAX_RESPONSE_BYTES + 10) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, big))) {
            CallToolResult result = run(stub, "get_usage", map());

            assertThat(result.isError()).isTrue();
            assertThat(structured(result).get("code").asText()).isEqualTo("api-invalid-response");
            assertThat(everything(result).length()).isLessThan(5000);
        }
    }

    @Test
    void aValueThatCannotBeSerializedIsAnInternalErrorNotAnException() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            CallToolResult result = run(stub, "check_listing",
                    map("marketplace", "ETSY", "listing", map("x", new Object())));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result).get("code").asText()).isEqualTo("internal-error");
            assertThat(stub.requests()).isEmpty();
        }
    }

    // ---- arguments ----------------------------------------------------------------------------------------------

    static Stream<Arguments> badArguments() {
        return Stream.of(
                Arguments.of("check_listing", map("listing", map()), "marketplace"),
                Arguments.of("check_listing", map("marketplace", "ETSY"), "listing"),
                Arguments.of("check_listing", map("marketplace", "  ", "listing", map()), "marketplace"),
                Arguments.of("check_listing", map("marketplace", "ETSY", "listing", "SECRET-VALUE-XYZ"), "listing"),
                Arguments.of("check_listing", map("marketplace", 5, "listing", map()), "marketplace"),
                Arguments.of("check_listing", map("marketplace", "ETSY", "listing", map(), "path", "/api/v1/admin"), "path"),
                Arguments.of("check_trademark", map("marketplace", "ETSY", "listing", List.of("SECRET-VALUE-XYZ")), "listing"),
                Arguments.of("check_variations", map("marketplace", "AMAZON", "children", "SECRET-VALUE-XYZ"), "children"),
                Arguments.of("check_variations", map("marketplace", "ETSY", "variation_properties", map()), "variation_properties"),
                Arguments.of("screen_personalization", map("marketplace", "ETSY"), "values"),
                Arguments.of("screen_personalization", map("marketplace", "ETSY", "values", "SECRET-VALUE-XYZ"), "values"),
                Arguments.of("screen_personalization", map("marketplace", "ETSY", "values", List.of(), "print_profile", 1), "print_profile"),
                Arguments.of("get_limits", map(), "marketplace"),
                Arguments.of("get_limits", map("marketplace", "ETSY", "productType", "x"), "productType"),
                Arguments.of("get_usage", map("marketplace", "ETSY"), "no arguments"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("badArguments")
    void badArgumentsAreRefusedBeforeAnyCallAndTheMessageNamesOnlyTheArgument(String tool, Map<String, Object> args,
            String mentioned) throws Exception {
        try (ApiStub stub = new ApiStub()) {
            CallToolResult result = run(stub, tool, args);

            assertThat(result.isError()).isTrue();
            JsonNode s = structured(result);
            assertThat(s.get("code").asText()).isEqualTo("invalid-arguments");
            assertThat(s.get("retryable").asBoolean()).isFalse();
            assertThat(s.get("detail").asText()).contains(mentioned);
            assertThat(everything(result)).doesNotContain("SECRET-VALUE-XYZ");
            assertThat(stub.requests()).as("nothing is sent").isEmpty();
        }
    }

    @Test
    void aNullArgumentMapIsTreatedAsNoArguments() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            ToolRunner runner = new ToolRunner(new ListingGuardApiClient(stub.config()), new ToolResults(M), M);

            assertThat(runner.run(tool("get_usage"), null).isError()).isFalse();
            assertThat(runner.run(tool("check_listing"), null).isError()).isTrue();
        }
    }

    // ---- secrets and personal data ------------------------------------------------------------------------------

    @Test
    void theKeyAppearsInNoResultAndNoLogOnAnyPath() throws Exception {
        String k = ApiStub.KEY;
        String hostile = "{\"code\":\"" + k + "\",\"title\":\"" + k + "\",\"detail\":\"key " + k + " rejected\","
                + "\"requestId\":\"" + k + "\",\"errors\":[{\"field\":\"" + k + "\",\"message\":\"" + k + "\"}],"
                + "\"plan\":\"" + k + "\",\"pack\":\"" + k + "\",\"resetAt\":\"" + k + "\",\"known\":[\"" + k + "\"],"
                + "\"echo\":\"" + k + "\"}";
        int[] statuses = {200, 400, 401, 403, 404, 429, 500, 503};
        for (int status : statuses) {
            for (ToolSpec spec : catalog.tools()) {
                try (ApiStub stub = new ApiStub().replying(Reply.problem(status, hostile).with("Retry-After", "5"))) {
                    CallToolResult result = run(stub, spec.name(), validArguments(spec.name()));

                    assertThat(everything(result)).as(spec.name() + " " + status).doesNotContain(k)
                            .doesNotContain("SECRETKEY");
                }
            }
        }
        // Failures that are not an answer.
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(2)))) {
            assertThat(everything(run(stub.config(Duration.ofMillis(200)), "get_usage", map())))
                    .doesNotContain(k);
        }
        assertThat(allLogs()).doesNotContain(k).doesNotContain("SECRETKEY");
        assertThat(allLogs()).contains("tool=get_usage");
    }

    @Test
    void noArgumentIsEverLogged() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(400, problem(400, "invalid-request", "")))) {
            run(stub, "check_listing", map("marketplace", "ETSY", "listing", map("title", "UNIQUE-TITLE-QWERTY")));
            run(stub, "screen_personalization", map("marketplace", "ETSY",
                    "values", List.of(map("label", "BUYER-LABEL-ZXCVB", "text", "BUYER-NAME-ASDFG"))));
            run(stub, "check_listing", map("marketplace", "ETSY", "listing", "BAD-ARGUMENT-VALUE-POIUY"));
            run(stub, "check_listing", map("marketplace", "ETSY", "listing", map("x", new Object())));
        }

        String log = allLogs();
        assertThat(log).contains("tool=check_listing").contains("tool=screen_personalization");
        assertThat(log).doesNotContain("QWERTY").doesNotContain("ASDFG").doesNotContain("ZXCVB")
                .doesNotContain("POIUY");
    }

    private static Map<String, Object> validArguments(String tool) {
        return switch (tool) {
            case "check_listing", "check_trademark" -> map("marketplace", "ETSY", "listing", map("title", "x"));
            case "check_variations" -> map("marketplace", "AMAZON", "children", List.of(map("sku", "C1")));
            case "screen_personalization" -> map("marketplace", "ETSY", "values", List.of(map("text", "A")));
            case "get_limits" -> map("marketplace", "ETSY");
            default -> map();
        };
    }
}
