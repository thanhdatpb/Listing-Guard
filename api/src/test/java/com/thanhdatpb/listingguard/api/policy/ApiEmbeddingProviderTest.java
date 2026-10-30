package com.thanhdatpb.listingguard.api.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider.Kind;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** {@link ApiEmbeddingProvider} against a local stub HTTP server. No test calls the real API. */
@ExtendWith(OutputCaptureExtension.class)
class ApiEmbeddingProviderTest {

    private static final String KEY = "test-secret-key-123";
    private static final String MODEL = "gemini-embedding-2";
    private static final int DIMENSIONS = 768;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Call(String method, String path, String query, String keyHeader, JsonNode body) {

        List<String> texts() {
            List<String> texts = new ArrayList<>();
            body.get("requests").forEach(request -> texts.add(request.get("content").get("parts").get(0).get("text").asText()));
            return texts;
        }
    }

    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Deque<Reply> script = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"), body));
            Reply reply = script.poll();
            if (reply == null) {
                reply = new Reply(200, embeddings(body, DIMENSIONS));
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
    }

    private ApiEmbeddingProvider provider(int batchSize) {
        return new ApiEmbeddingProvider(baseUrl(), MODEL, DIMENSIONS, KEY, batchSize, Duration.ofSeconds(5), Duration.ofMillis(1));
    }

    /** One vector per request; the first value is the length of the sent text, to tell the texts apart. */
    private static String embeddings(JsonNode body, int dimensions) {
        StringBuilder out = new StringBuilder("{\"embeddings\":[");
        boolean first = true;
        for (JsonNode request : body.get("requests")) {
            int length = request.get("content").get("parts").get(0).get("text").asText().length();
            out.append(first ? "" : ",").append(vector(length, dimensions));
            first = false;
        }
        return out.append("]}").toString();
    }

    private static String vector(float first, int dimensions) {
        StringBuilder values = new StringBuilder("{\"values\":[").append(first);
        for (int i = 1; i < dimensions; i++) {
            values.append(",0.5");
        }
        return values.append("]}").toString();
    }

    // ---- batching and request shape --------------------------------------------------------------------------

    @Test
    void sendsTextsInBatchesOfTheConfiguredSizeAndReturnsOneVectorPerTextInOrder() {
        List<String> texts = List.of("a", "bb", "ccc", "dddd", "eeeee");

        List<float[]> vectors = provider(2).embed(texts, Kind.DOCUMENT);

        assertThat(calls).extracting(call -> call.body().get("requests").size()).containsExactly(2, 2, 1);
        assertThat(vectors).hasSize(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            assertThat(vectors.get(i)).hasSize(DIMENSIONS);
            assertThat(vectors.get(i)[0]).isEqualTo((ApiEmbeddingProvider.DOCUMENT_PREFIX + texts.get(i)).length());
        }
    }

    @Test
    void everyTextIsItsOwnRequestWithOneContentSoTheModelCannotMergeThemIntoOneVector() {
        provider(16).embed(List.of("first", "second", "third"), Kind.DOCUMENT);

        assertThat(calls).hasSize(1);
        JsonNode requests = calls.get(0).body().get("requests");
        assertThat(requests).hasSize(3);
        for (JsonNode request : requests) {
            assertThat(request.get("model").asText()).isEqualTo("models/" + MODEL);
            assertThat(request.get("content").get("parts")).hasSize(1);
            assertThat(request.get("outputDimensionality").asInt()).isEqualTo(DIMENSIONS);
        }
    }

    @Test
    void anEmptyListSendsNothing() {
        assertThat(provider(16).embed(List.of(), Kind.QUERY)).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void usesTheDocumentedEndpointAndSendsTheKeyInAHeaderNeverInTheUrl() {
        provider(16).embed(List.of("x"), Kind.DOCUMENT);

        Call call = calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/v1beta/models/" + MODEL + ":batchEmbedContents");
        assertThat(call.query()).isNull();
        assertThat(call.keyHeader()).isEqualTo(KEY);
    }

    @Test
    void aTrailingSlashOnTheBaseUrlAndWhitespaceAroundTheKeyAreTolerated() {
        new ApiEmbeddingProvider(baseUrl() + "/", MODEL, DIMENSIONS, " " + KEY + "\n", 16, Duration.ofSeconds(5), Duration.ofMillis(1))
                .embed(List.of("x"), Kind.QUERY);

        assertThat(calls.get(0).path()).isEqualTo("/v1beta/models/" + MODEL + ":batchEmbedContents");
        assertThat(calls.get(0).keyHeader()).isEqualTo(KEY);
    }

    // ---- Kind -> prefix --------------------------------------------------------------------------------------

    @Test
    void mapsKindToThePrefixesOfTheGeminiEmbeddingGuide() {
        provider(16).embed(List.of("seller rules"), Kind.DOCUMENT);
        provider(16).embed(List.of("can I sell prints"), Kind.QUERY);

        assertThat(calls.get(0).texts()).containsExactly("title: none | text: seller rules");
        assertThat(calls.get(1).texts()).containsExactly("task: search result | query: can I sell prints");
    }

    @Test
    void modelIdNamesProviderModelAndDimensions() {
        assertThat(provider(16).modelId()).isEqualTo("api:google:gemini-embedding-2:768");
        assertThat(provider(16).dimensions()).isEqualTo(DIMENSIONS);
    }

    // ---- response checks -------------------------------------------------------------------------------------

    @Test
    void aVectorWithTheWrongNumberOfDimensionsIsRejected() {
        script.add(new Reply(200, "{\"embeddings\":[" + vector(1, 3) + "]}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("x"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("3 dimensions")
                .hasMessageContaining("expected " + DIMENSIONS);
    }

    @Test
    void fewerVectorsThanTextsIsRejected() {
        script.add(new Reply(200, "{\"embeddings\":[" + vector(1, DIMENSIONS) + "]}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("a", "b"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("1 embeddings for 2 texts");
    }

    @Test
    void moreVectorsThanTextsIsRejected() {
        script.add(new Reply(200, "{\"embeddings\":[" + vector(1, DIMENSIONS) + "," + vector(2, DIMENSIONS) + "]}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("a"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("2 embeddings for 1 texts");
    }

    @Test
    void aResponseWithoutEmbeddingsOrValuesIsRejected() {
        script.add(new Reply(200, "{}"));
        script.add(new Reply(200, "{\"embeddings\":[{}]}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("a"), Kind.DOCUMENT)).isInstanceOf(EmbeddingException.class);
        assertThatThrownBy(() -> provider(16).embed(List.of("a"), Kind.DOCUMENT)).isInstanceOf(EmbeddingException.class);
    }

    @Test
    void aResponseThatIsNotJsonIsRejectedWithAnEmbeddingException() {
        script.add(new Reply(200, "<html>not json</html>"));

        assertThatThrownBy(() -> provider(16).embed(List.of("a"), Kind.DOCUMENT)).isInstanceOf(EmbeddingException.class);
    }

    // ---- retries ---------------------------------------------------------------------------------------------

    @Test
    void ingestRetriesA429AndThen200Succeeds() {
        script.add(new Reply(429, "{\"error\":{\"message\":\"quota\"}}"));

        List<float[]> vectors = provider(16).embed(List.of("x"), Kind.DOCUMENT);

        assertThat(vectors).hasSize(1);
        assertThat(calls).hasSize(2);
    }

    @Test
    void ingestRetries5xxAndStopsAfterThreeRetries() {
        for (int i = 0; i < 10; i++) {
            script.add(new Reply(503, "{\"error\":{\"message\":\"overloaded\"}}"));
        }

        assertThatThrownBy(() -> provider(16).embed(List.of("x"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 503");

        assertThat(calls).hasSize(1 + ApiEmbeddingProvider.DOCUMENT_RETRIES);
    }

    @Test
    void theRequestPathRetriesAtMostOnce() {
        for (int i = 0; i < 10; i++) {
            script.add(new Reply(500, "{}"));
        }

        assertThatThrownBy(() -> provider(16).embed(List.of("x"), Kind.QUERY)).isInstanceOf(EmbeddingException.class);

        assertThat(calls).hasSize(1 + ApiEmbeddingProvider.QUERY_RETRIES);
    }

    @Test
    void aQueryThatFailsOnceSucceedsOnTheRetry() {
        script.add(new Reply(429, "{}"));

        assertThat(provider(16).embed(List.of("x"), Kind.QUERY)).hasSize(1);
        assertThat(calls).hasSize(2);
    }

    @Test
    void otherClientErrorsAreNotRetried() {
        script.add(new Reply(400, "{\"error\":{\"message\":\"bad request\"}}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("x"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 400");

        assertThat(calls).hasSize(1);
    }

    // ---- the key never leaks ---------------------------------------------------------------------------------

    @Test
    void anErrorBodyThatEchoesTheKeyIsScrubbedFromTheMessageAndTheLog(CapturedOutput output) {
        script.add(new Reply(429, "{\"error\":{\"message\":\"quota for key " + KEY + "\"}}"));
        script.add(new Reply(401, "{\"error\":{\"message\":\"API key " + KEY + " is not valid\"}}"));

        assertThatThrownBy(() -> provider(16).embed(List.of("x"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("HTTP 401").doesNotContain(KEY).contains("***");
                    assertThat(e.getCause()).isNull();
                });
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test
    void anUnreachableServerGivesAMessageWithoutTheKey(CapturedOutput output) {
        ApiEmbeddingProvider unreachable = new ApiEmbeddingProvider("http://127.0.0.1:1/v1beta", MODEL, DIMENSIONS, KEY, 16,
                Duration.ofSeconds(2), Duration.ofMillis(1));

        assertThatThrownBy(() -> unreachable.embed(List.of("x"), Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test
    void aRetriedCallLogsNoKey(CapturedOutput output) {
        script.add(new Reply(503, "{}"));

        provider(16).embed(List.of("x"), Kind.DOCUMENT);

        assertThat(output.getAll()).contains("retrying").doesNotContain(KEY);
    }

    @Test
    void anEmptyKeyIsRefusedByTheConstructor() {
        assertThatThrownBy(() -> new ApiEmbeddingProvider(baseUrl(), MODEL, DIMENSIONS, "  ", 16, Duration.ofSeconds(5), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiEmbeddingProvider(baseUrl(), MODEL, DIMENSIONS, null, 16, Duration.ofSeconds(5), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchSizeBelowOneIsRefused() {
        assertThatThrownBy(() -> new ApiEmbeddingProvider(baseUrl(), MODEL, DIMENSIONS, KEY, 0, Duration.ofSeconds(5), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
