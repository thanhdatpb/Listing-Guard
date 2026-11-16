package com.thanhdatpb.listingguard.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapter against a local stub HTTP server: no real vendor is called. */
class OpenAiCompatibleProviderTest {

    private static final String KEY = "sk-test-secret-key-123456";
    private static final LlmRequest REQUEST = new LlmRequest("Judge the word.", "Sentence about [[Dodger blue]].", "{\"type\":\"object\"}",
            200, Duration.ofSeconds(2));

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer;
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        answer = completion("{\"verdict\":\"unsure\",\"reason\":\"x\"}", "stop");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleProvider provider(boolean jsonMode) {
        return new OpenAiCompatibleProvider("stub", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "model-1", KEY, jsonMode);
    }

    private static String completion(String content, String finishReason) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + new ObjectMapper().valueToTree(content)
                + "},\"finish_reason\":\"" + finishReason + "\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7}}";
    }

    @Test
    void sendsTheRequestAndReadsTheAnswer() throws IOException {
        LlmResponse response = provider(true).complete(REQUEST);

        assertThat(response.text()).isEqualTo("{\"verdict\":\"unsure\",\"reason\":\"x\"}");
        assertThat(response.inputTokens()).isEqualTo(42);
        assertThat(response.outputTokens()).isEqualTo(7);
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("model").asText()).isEqualTo("model-1");
        assertThat(sent.get("temperature").asInt()).isZero();
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(200);
        assertThat(sent.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(sent.get("messages").get(0).get("content").asText()).contains("Judge the word.", "\"type\":\"object\"");
        assertThat(sent.get("messages").get(1).get("content").asText()).isEqualTo("Sentence about [[Dodger blue]].");
    }

    @Test
    void jsonModeCanBeTurnedOffForAVendorThatRejectsIt() throws IOException {
        provider(false).complete(REQUEST);

        assertThat(json.readTree(body.get()).has("response_format")).isFalse();
    }

    @Test
    void mapsStatusesToTheKindsTheClientFailsOverOn() {
        status = 429;
        answer = "{\"error\":\"slow down " + KEY + "\"}";
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMIT));

        status = 503;
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BAD_STATUS));
    }

    @Test
    void aTimeoutIsATimeout() {
        delayMillis = 1500;
        LlmRequest quick = new LlmRequest("s", "u", "{}", 10, Duration.ofMillis(200));

        assertThatThrownBy(() -> provider(true).complete(quick)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT));
    }

    @Test
    void aCutAnswerAndAnUnreadableAnswerAreFailures() {
        answer = completion("{\"verdict\":", "length");
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TRUNCATED));

        answer = "not json at all";
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BAD_STATUS));

        answer = "{\"choices\":[]}";
        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOf(LlmException.class);
    }

    @Test
    void theKeyNeverAppearsInAMessageAndAnEmptyKeyIsRefused() {
        status = 401;
        answer = "{\"error\":\"bad key " + KEY + "\"}";

        assertThatThrownBy(() -> provider(true).complete(REQUEST)).isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY, "Dodger"));
        assertThatThrownBy(() -> new OpenAiCompatibleProvider("stub", "http://x", "m", " ", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
        assertThat(new LlmProperties.Provider("groq", "http://x", "m", KEY, 1, 1, false, false, true).toString()).doesNotContain(KEY);
    }
}
