package com.thanhdatpb.listingguard.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * An OpenAI-compatible Chat Completions endpoint (ADR-036 item 1): Groq, Cloudflare Workers AI and Google Gemini all answer
 * {@code POST {base-url}/chat/completions} with {@code Authorization: Bearer <key>}. Temperature 0, one request, no retry.
 *
 * <p>The key is sent in the header only. No message of an exception carries the key, a prompt, a response body or listing text:
 * a failure says the status or the kind and nothing else. {@code response_format: json_object} is sent unless {@code jsonMode} is
 * off (a vendor that rejects it); the caller validates the JSON in any case.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String id;
    private final URI endpoint;
    private final String model;
    private final String key;
    private final boolean jsonMode;

    public OpenAiCompatibleProvider(String id, String baseUrl, String model, String key, boolean jsonMode) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The API key of LLM provider '" + id + "' is empty");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.id = id;
        this.endpoint = URI.create(base + "/chat/completions");
        this.model = model;
        this.key = key.strip();
        this.jsonMode = jsonMode;
    }

    @Override
    public String modelId() {
        return id + ":" + model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        ObjectNode body = chatPayload(json, model, request, jsonMode);

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(request.timeout())
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "LLM provider " + id + " timed out");
        } catch (IOException e) {
            // a connect timeout is an IOException too; the text of the exception is not used: it can name the URL
            throw new LlmException(LlmException.Kind.BAD_STATUS, "Cannot reach LLM provider " + id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TIMEOUT, "Interrupted while waiting for LLM provider " + id);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new LlmException(LlmException.Kind.RATE_LIMIT, "LLM provider " + id + " answered HTTP 429");
        }
        if (status < 200 || status > 299) {
            throw new LlmException(LlmException.Kind.BAD_STATUS, "LLM provider " + id + " answered HTTP " + status);
        }
        return parse(response.body());
    }

    static ObjectNode chatPayload(ObjectMapper json, String model, LlmRequest request, boolean jsonMode) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", request.maxOutputTokens());
        if (jsonMode) {
            body.putObject("response_format").put("type", "json_object");
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", request.system() + "\n\nAnswer with one JSON object that matches this JSON Schema:\n"
                        + request.jsonSchema());
        messages.addObject().put("role", "user").put("content", request.user());
        return body;
    }

    private LlmResponse parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.BAD_STATUS, "LLM provider " + id + " returned an answer that is not JSON");
        }
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        JsonNode content = choice == null ? null : choice.path("message").path("content");
        if (content == null || !content.isTextual()) {
            throw new LlmException(LlmException.Kind.BAD_STATUS, "LLM provider " + id + " returned no answer text");
        }
        String finish = choice.path("finish_reason").asText("");
        if ("length".equals(finish)) {
            throw new LlmException(LlmException.Kind.TRUNCATED, "LLM provider " + id + " cut the answer at the token limit");
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(content.asText(), usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), finish);
    }
}
