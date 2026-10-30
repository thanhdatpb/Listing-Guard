package com.thanhdatpb.listingguard.api.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Embeddings from the Google Gemini API ({@code gemini-embedding-2}), the default provider (ADR-012). Everything
 * that is specific to the vendor lives in this class.
 *
 * <p>Documentation: <a href="https://ai.google.dev/gemini-api/docs/embeddings">embeddings guide</a> and
 * <a href="https://ai.google.dev/api/embeddings">API reference</a>, read on 2026-10-30.
 * <ul>
 *   <li>{@code gemini-embedding-2} does not support {@code task_type}; the guide asks for a text prefix instead:
 *       {@code task: search result | query: {content}} for a query and {@code title: {title} | text: {content}}
 *       for a document, and "If there is no title, use {@code title: none}". {@code EmbeddingProvider} passes no
 *       title (the section path is already part of a chunk's embedding text), so documents use {@code title: none}.</li>
 *   <li>"Adding multiple inputs directly to the {@code contents} parameter produces one aggregated embedding for all
 *       inputs". So every text is its own request inside one {@code models/{model}:batchEmbedContents} call, and
 *       the number of vectors returned is checked against the number of texts.</li>
 *   <li>The key travels in the {@code x-goog-api-key} header, never in the URL.</li>
 * </ul>
 *
 * <p>The vendor does not state a maximum batch size, so it is configurable. A request that gets HTTP 429 or 5xx
 * is retried with a doubling backoff: documents (embedded by the ingest job) up to {@value #DOCUMENT_RETRIES}
 * times, queries (embedded while a request waits) once. The key is never logged and never part of a message;
 * any text that came from the network is scrubbed of it anyway.
 */
public final class ApiEmbeddingProvider implements EmbeddingProvider {

    public static final String DOCUMENT_PREFIX = "title: none | text: ";
    public static final String QUERY_PREFIX = "task: search result | query: ";
    public static final String KEY_HEADER = "x-goog-api-key";

    static final int DOCUMENT_RETRIES = 3;
    static final int QUERY_RETRIES = 1;

    private static final Logger log = LoggerFactory.getLogger(ApiEmbeddingProvider.class);
    private static final int ERROR_BODY_LIMIT = 300;

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient client;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final Duration retryBackoff;
    private final String apiKey;

    public ApiEmbeddingProvider(String baseUrl, String model, int dimensions, String apiKey, int batchSize,
                                Duration timeout, Duration retryBackoff) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("The embedding API key is empty");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("listing-guard.embedding.api.batch-size must be at least 1, got " + batchSize);
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("listing-guard.embedding.api.dimensions must be at least 1, got " + dimensions);
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.retryBackoff = retryBackoff;
        this.apiKey = apiKey.strip();

        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requests)
                .defaultHeader(KEY_HEADER, this.apiKey)
                .build();
    }

    @Override
    public String modelId() {
        return "api:google:" + model + ":" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        String prefix = kind == Kind.QUERY ? QUERY_PREFIX : DOCUMENT_PREFIX;
        int retries = kind == Kind.QUERY ? QUERY_RETRIES : DOCUMENT_RETRIES;
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            vectors.addAll(embedBatch(texts.subList(from, Math.min(from + batchSize, texts.size())), prefix, retries));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch, String prefix, int retries) {
        ObjectNode body = json.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        for (String text : batch) {
            ObjectNode request = requests.addObject().put("model", "models/" + model);
            request.putObject("content").putArray("parts").addObject().put("text", prefix + text);
            request.put("outputDimensionality", dimensions);
        }
        String payload = body.toString();

        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode response = client.post()
                        .uri("/models/{model}:batchEmbedContents", model)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(JsonNode.class);
                return parse(response, batch.size());
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (retryable(status) && attempt < retries) {
                    log.warn("Embedding API answered HTTP {}, retrying ({}/{})", status, attempt + 1, retries);
                    pause(attempt);
                    continue;
                }
                throw new EmbeddingException(scrub("The embedding API answered HTTP " + status + " for model " + model + ": "
                        + excerpt(e.getResponseBodyAsString())));
            } catch (ResourceAccessException e) {
                throw new EmbeddingException(scrub("Cannot reach the embedding API at " + baseUrl + ": " + e.getMessage()));
            } catch (RestClientException e) {
                throw new EmbeddingException(scrub("The embedding API returned a response that cannot be read: " + e.getMessage()));
            }
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(retryBackoff.toMillis() << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while waiting to retry the embedding API");
        }
    }

    private List<float[]> parse(JsonNode response, int expected) {
        JsonNode embeddings = response == null ? null : response.get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != expected) {
            throw new EmbeddingException("The embedding API returned " + (embeddings == null || !embeddings.isArray() ? "no"
                    : String.valueOf(embeddings.size())) + " embeddings for " + expected + " texts");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode embedding : embeddings) {
            JsonNode values = embedding.get("values");
            if (values == null || !values.isArray() || values.size() != dimensions) {
                throw new EmbeddingException("Model " + model + " returned "
                        + (values == null || !values.isArray() ? "no values" : values.size() + " dimensions")
                        + ", expected " + dimensions);
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                JsonNode value = values.get(i);
                if (!value.isNumber()) {
                    throw new EmbeddingException("Model " + model + " returned a value that is not a number");
                }
                vector[i] = (float) value.asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static String excerpt(String text) {
        String body = text == null ? "" : text;
        return body.substring(0, Math.min(ERROR_BODY_LIMIT, body.length()));
    }

    /** Defence in depth: text that came from the network or a library never carries the key into a message. */
    private String scrub(String message) {
        return message.replace(apiKey, "***");
    }
}
