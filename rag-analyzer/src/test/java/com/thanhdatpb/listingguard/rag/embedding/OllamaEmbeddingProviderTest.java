package com.thanhdatpb.listingguard.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider.Kind;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a fake Ollama (JDK HttpServer), so no real Ollama is needed. */
class OllamaEmbeddingProviderTest {

    private static final int DIMENSIONS = 4;

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int responseStatus = 200;
    private String responseOverride;
    private int responseDimensions = DIMENSIONS;

    @BeforeEach
    void startFakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            requests.add(request);
            String body = responseOverride != null ? responseOverride : embeddingsFor(request.get("input").size());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeOllama() {
        server.stop(0);
    }

    private String embeddingsFor(int count) {
        ObjectNode root = json.createObjectNode();
        ArrayNode embeddings = root.putArray("embeddings");
        for (int i = 0; i < count; i++) {
            ArrayNode vector = embeddings.addArray();
            for (int d = 0; d < responseDimensions; d++) {
                vector.add(i + d / 10.0);
            }
        }
        return root.toString();
    }

    private OllamaEmbeddingProvider provider() {
        return OllamaEmbeddingProvider.nomic("http://127.0.0.1:" + server.getAddress().getPort() + "/", "nomic-embed-text",
                DIMENSIONS, Duration.ofSeconds(5));
    }

    @Test
    void sendsModelAndDocumentPrefix() {
        List<float[]> vectors = provider().embed(List.of("clause one", "clause two"), Kind.DOCUMENT);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("model").asText()).isEqualTo("nomic-embed-text");
        assertThat(requests.get(0).get("input").get(0).asText()).isEqualTo("search_document: clause one");
        assertThat(requests.get(0).get("input").get(1).asText()).isEqualTo("search_document: clause two");
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(1)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(1)).hasSize(DIMENSIONS);
    }

    @Test
    void queriesGetTheQueryPrefix() {
        provider().embed("can I sell replicas", Kind.QUERY);

        assertThat(requests.get(0).get("input").get(0).asText()).isEqualTo("search_query: can I sell replicas");
    }

    @Test
    void largeInputsAreSplitIntoBatchesAndKeepTheirOrder() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add("text " + i);
        }

        List<float[]> vectors = provider().embed(texts, Kind.DOCUMENT);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("input")).hasSize(16);
        assertThat(requests.get(1).get("input")).hasSize(4);
        assertThat(vectors).hasSize(20);
    }

    @Test
    void reportsTheModelId() {
        assertThat(provider().modelId()).isEqualTo("ollama:nomic-embed-text:4");
        assertThat(provider().dimensions()).isEqualTo(DIMENSIONS);
    }

    @Test
    void wrongDimensionsAreRejected() {
        responseDimensions = 7;

        assertThatThrownBy(() -> provider().embed("x", Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("7 dimensions")
                .hasMessageContaining("expected 4");
    }

    @Test
    void wrongNumberOfEmbeddingsIsRejected() {
        responseOverride = "{\"embeddings\": []}";

        assertThatThrownBy(() -> provider().embed("x", Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("0 embeddings for 1 inputs");
    }

    @Test
    void httpErrorsIncludeTheStatusAndBody() {
        responseStatus = 404;
        responseOverride = "{\"error\":\"model \\\"nomic-embed-text\\\" not found, try pulling it first\"}";

        assertThatThrownBy(() -> provider().embed("x", Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("not found");
    }

    @Test
    void unreachableServerGivesAHelpfulMessage() {
        server.stop(0);

        assertThatThrownBy(() -> provider().embed("x", Kind.DOCUMENT))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Cannot reach Ollama")
                .hasMessageContaining("ollama pull nomic-embed-text");
    }
}
