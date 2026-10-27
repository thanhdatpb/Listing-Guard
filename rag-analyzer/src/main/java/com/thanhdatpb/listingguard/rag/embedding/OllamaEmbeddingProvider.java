package com.thanhdatpb.listingguard.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeddings from a local Ollama server ({@code POST /api/embed}). Texts are sent in small batches.
 * The model is only contacted when something is embedded, never at construction, so the application
 * starts without Ollama.
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    /** Task prefixes required by nomic-embed-text; other models can pass empty strings. */
    public static final String NOMIC_DOCUMENT_PREFIX = "search_document: ";
    public static final String NOMIC_QUERY_PREFIX = "search_query: ";

    private static final int BATCH_SIZE = 16;
    private static final int ERROR_BODY_LIMIT = 300;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI embedUri;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final Duration timeout;
    private final String documentPrefix;
    private final String queryPrefix;

    public OllamaEmbeddingProvider(String baseUrl, String model, int dimensions, Duration timeout,
                                   String documentPrefix, String queryPrefix) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = trimmed;
        this.embedUri = URI.create(trimmed + "/api/embed");
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** nomic-embed-text with its task prefixes. */
    public static OllamaEmbeddingProvider nomic(String baseUrl, String model, int dimensions, Duration timeout) {
        return new OllamaEmbeddingProvider(baseUrl, model, dimensions, timeout, NOMIC_DOCUMENT_PREFIX, NOMIC_QUERY_PREFIX);
    }

    @Override
    public String modelId() {
        return "ollama:" + model + ":" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        String prefix = kind == Kind.QUERY ? queryPrefix : documentPrefix;
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            vectors.addAll(embedBatch(batch, prefix));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch, String prefix) {
        ObjectNode body = json.createObjectNode().put("model", model);
        ArrayNode input = body.putArray("input");
        batch.forEach(text -> input.add(prefix + text));

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(embedUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingException("Cannot reach Ollama at " + baseUrl + ". Is it running, and is the model pulled ("
                    + "ollama pull " + model + ")? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while waiting for Ollama", e);
        }
        if (response.statusCode() != 200) {
            String text = response.body() == null ? "" : response.body();
            throw new EmbeddingException("Ollama returned HTTP " + response.statusCode() + " for model " + model + ": "
                    + text.substring(0, Math.min(ERROR_BODY_LIMIT, text.length())));
        }
        return parse(response.body(), batch.size());
    }

    private List<float[]> parse(String responseBody, int expected) {
        JsonNode embeddings;
        try {
            embeddings = json.readTree(responseBody).get("embeddings");
        } catch (IOException e) {
            throw new EmbeddingException("Ollama returned invalid JSON", e);
        }
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != expected) {
            throw new EmbeddingException("Ollama returned " + (embeddings == null ? "no" : embeddings.size())
                    + " embeddings for " + expected + " inputs");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode embedding : embeddings) {
            if (embedding.size() != dimensions) {
                throw new EmbeddingException("Model " + model + " returned " + embedding.size()
                        + " dimensions, expected " + dimensions);
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
