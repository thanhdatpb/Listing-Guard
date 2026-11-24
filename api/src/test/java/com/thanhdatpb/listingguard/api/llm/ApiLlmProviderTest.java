package com.thanhdatpb.listingguard.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmException.Kind;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ApiLlmProviderTest {
    private static final String KEY = "fake-policy-secret";
    private static final String LISTING = "synthetic-private-listing";
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final String MODEL = "model-2026-10-01";
    private static final LlmRequest REQUEST = new LlmRequest("Fixed system", LISTING, SCHEMA, 200, Duration.ofSeconds(2));

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private volatile String authorization;
    private volatile String payload;
    private volatile String answer;
    private volatile int status = 200;
    private volatile long headerDelay;
    private volatile long bodyDelay;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        answer = completion("{\"findings\":[]}", "stop");
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                Thread.sleep(headerDelay);
                exchange.sendResponseHeaders(status, bytes.length);
                Thread.sleep(bodyDelay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void policyProviderIsOptInAndRequiresItsDedicatedKeyOnlyWhenEnabled() {
        var config = new ApplicationContextRunner().withUserConfiguration(PolicyLlmConfig.class)
                .withPropertyValues("LISTING_GUARD_ANALYZER_LLM_API_KEY=");
        config.run(context -> assertThat(context).doesNotHaveBean(LlmProvider.class));
        var enabled = config.withPropertyValues("listing-guard.analyzer.enabled=true",
                "listing-guard.analyzer.llm.base-url=" + baseUrl(), "listing-guard.analyzer.llm.model=" + MODEL);
        enabled.run(context -> assertThat(context).hasFailed());
        enabled.withPropertyValues("LISTING_GUARD_ANALYZER_LLM_API_KEY=" + KEY)
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmProvider.class);
                    assertThat(context.getBean(LlmProvider.class).complete(REQUEST).text()).isEqualTo("{\"findings\":[]}");
                });
    }

    @Test
    void sendsOneStructuredRequestAndReturnsOnlyBoundedUsage() throws IOException {
        var provider = provider(true);
        var response = provider.complete(REQUEST);
        assertThat(provider.modelId()).isEqualTo("api:127.0.0.1:" + MODEL);
        assertThat(response.text()).isEqualTo("{\"findings\":[]}");
        assertThat(response.inputTokens()).isEqualTo(42);
        assertThat(response.outputTokens()).isEqualTo(7);
        assertThat(response.reportedCost()).isEqualByComparingTo("0.00025");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(authorization).isEqualTo("Bearer " + KEY);
        var sent = json.readTree(payload);
        assertThat(sent.path("max_tokens").intValue()).isEqualTo(200);
        assertThat(sent.path("temperature").intValue()).isZero();
        assertThat(sent.path("response_format").path("type").textValue()).isEqualTo("json_object");
        assertThat(sent.path("messages").path(0).path("content").textValue()).contains(SCHEMA).doesNotContain(LISTING);
        assertThat(sent.path("messages").path(1).path("content").textValue()).isEqualTo(LISTING);
        assertThat(sent.has("tools")).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void acceptsValidJsonWithOrWithoutTheVendorJsonHint() throws IOException {
        answer = completion("{\"findings\":[]}", "stop").replace("0.00025", "-2.0");
        assertThat(provider(false).complete(REQUEST).reportedCost()).isNull();
        assertThat(json.readTree(payload).has("response_format")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503, 307})
    void statusErrorsDoNotRetryOrExposeTheProviderResponse(int httpStatus) {
        status = httpStatus;
        answer = KEY + LISTING;
        fails(httpStatus == 429 ? Kind.RATE_LIMIT : Kind.BAD_STATUS);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void capsTimeToHeadersAndBody(boolean beforeHeaders) {
        headerDelay = beforeHeaders ? 1_000 : 0;
        bodyDelay = beforeHeaders ? 0 : 1_000;
        var request = new LlmRequest("system", LISTING, SCHEMA, 200, Duration.ofMillis(300));
        assertThatThrownBy(() -> provider(true).complete(request)).isInstanceOfSatisfying(LlmException.class,
                error -> assertThat(error.kind()).isEqualTo(Kind.TIMEOUT));
    }

    @Test
    void mapsConnectionTimeoutWithoutExposingItsCause() {
        HttpClient transport = mock(HttpClient.class);
        when(transport.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenReturn(CompletableFuture.failedFuture(new HttpConnectTimeoutException(KEY + LISTING)));
        var provider = new ApiLlmProvider(baseUrl(), MODEL, KEY, true, transport);
        assertThatThrownBy(() -> provider.complete(REQUEST)).isInstanceOfSatisfying(LlmException.class, error -> {
            assertThat(error.kind()).isEqualTo(Kind.TIMEOUT);
            assertThat(error).hasNoCause();
            assertThat(error.getMessage()).doesNotContain(KEY, LISTING);
        });
    }

    @Test
    void rejectsInvalidEnvelopeAndModelJson() {
        answer = "not json " + LISTING;
        fails(Kind.INVALID_OUTPUT);
        answer = completion("{broken", "stop");
        fails(Kind.INVALID_OUTPUT);
        answer = completion("{} {}", "stop");
        fails(Kind.INVALID_OUTPUT);
        answer = completion("{\"findings\":[],\"findings\":[]}", "stop");
        fails(Kind.INVALID_OUTPUT);
    }

    @Test
    void detectsTruncationRefusalAndUnexpectedToolCalls() {
        answer = completion("{", "length");
        fails(Kind.TRUNCATED);
        answer = completion("", "content_filter");
        fails(Kind.REFUSED);
        answer = completion("{}", "stop").replace("\"content\":", "\"refusal\":\"private refusal\",\"content\":");
        fails(Kind.REFUSED);
        answer = completion("{}", "stop").replace("\"content\":", "\"tool_calls\":[{}],\"content\":");
        fails(Kind.INVALID_OUTPUT);
    }

    @Test
    void rejectsExcessiveReportedTokensTextAndHttpBody() {
        answer = completion("{}", "stop").replace("\"completion_tokens\":7", "\"completion_tokens\":201");
        fails(Kind.TRUNCATED);
        answer = completion("{\"f\":\"" + "x".repeat(70_000) + "\"}", "stop");
        fails(Kind.TRUNCATED);
        answer = "x".repeat(525_000);
        fails(Kind.TRUNCATED);
    }

    @Test
    void restClientDebugLoggingDoesNotPrintThePromptOrCredentials(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.web.client");
        Level previous = logger.getLevel();
        logger.setLevel(Level.TRACE);
        try {
            provider(true).complete(REQUEST);
            answer = completion(LISTING + KEY, "stop");
            fails(Kind.INVALID_OUTPUT);
            assertThat(output.getAll()).doesNotContain(LISTING, KEY);
        } finally {
            logger.setLevel(previous);
        }
    }

    private ApiLlmProvider provider(boolean jsonMode) {
        return new ApiLlmProvider(baseUrl(), MODEL, KEY, jsonMode);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    private void fails(Kind kind) {
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOfSatisfying(LlmException.class, error -> {
            assertThat(error.kind()).isEqualTo(kind);
            assertThat(error).hasNoCause();
            assertThat(error.getMessage()).doesNotContain(KEY, LISTING);
        });
    }

    private String completion(String content, String finishReason) {
        return "{\"choices\":[{\"message\":{\"content\":" + json.getNodeFactory().textNode(content)
                + "},\"finish_reason\":\"" + finishReason
                + "\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7,\"cost\":0.00025}}";
    }
}
