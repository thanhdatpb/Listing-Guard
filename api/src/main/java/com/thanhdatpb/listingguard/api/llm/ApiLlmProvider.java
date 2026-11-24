package com.thanhdatpb.listingguard.api.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmException.Kind;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** ADR-011 PR 3. The transport validates the completion; policy findings and citations are verified separately. */
public final class ApiLlmProvider implements LlmProvider {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TOKENS = 1_500;
    private static final int MAX_TEXT_CHARS = 65_536;
    private static final int MAX_HTTP_BYTES = 512 * 1024;
    private static final int MAX_USAGE_TOKENS = 10_000_000;
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final RestClient client;
    private final URI endpoint;
    private final String model;
    private final String key;
    private final boolean jsonMode;

    public ApiLlmProvider(String baseUrl, String model, String key, boolean jsonMode) {
        this(baseUrl, model, key, jsonMode, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ApiLlmProvider(String baseUrl, String model, String key, boolean jsonMode, HttpClient http) {
        if (key == null || key.isBlank() || !key.strip().matches("[!-~]+")) {
            throw new IllegalArgumentException("Set a valid LISTING_GUARD_ANALYZER_LLM_API_KEY when the policy analyzer is enabled");
        }
        if (model == null || !model.matches("[a-zA-Z0-9._:/-]{1,200}")) {
            throw new IllegalArgumentException("Set a versioned listing-guard.analyzer.llm.model");
        }
        this.endpoint = endpoint(baseUrl);
        this.model = model;
        this.key = key.strip();
        this.jsonMode = jsonMode;
        this.client = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(http)).build();
    }

    @Override
    public String modelId() {
        return "api:" + endpoint.getHost() + ":" + model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request.maxOutputTokens() > MAX_TOKENS) {
            throw failure(Kind.INVALID_OUTPUT, "Invalid policy LLM request limits or schema");
        }
        readObject(request.jsonSchema().getBytes(StandardCharsets.UTF_8));
        byte[] payload = OpenAiCompatibleProvider.chatPayload(json, model, request, jsonMode)
                .toString().getBytes(StandardCharsets.UTF_8);
        Duration timeout = request.timeout().compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : request.timeout();
        // Bound the caller's wait even if the provider sends headers and then stops sending body bytes.
        var exchange = new FutureTask<>(() -> exchange(payload, request.maxOutputTokens()));
        Thread.startVirtualThread(exchange);
        try {
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw failure(Kind.TIMEOUT, "Policy LLM request timed out");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(Kind.TIMEOUT, "Interrupted while waiting for policy LLM");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmException known) {
                throw known;
            }
            throw failure(Kind.BAD_STATUS, "Cannot read the policy LLM response");
        }
    }

    private LlmResponse exchange(byte[] payload, int maxTokens) {
        try {
            return client.post().uri(endpoint).headers(headers -> headers.setBearerAuth(key))
                    // The streaming callback bypasses RestClient's message-converter debug dump of the body.
                    .contentType(MediaType.APPLICATION_JSON).contentLength(payload.length)
                    .body(output -> output.write(payload))
                    .exchange((sent, reply) -> {
                        try (var input = reply.getBody()) {
                            int status = reply.getStatusCode().value();
                            if (status == 429) {
                                throw failure(Kind.RATE_LIMIT, "Policy LLM returned HTTP 429");
                            }
                            if (!reply.getStatusCode().is2xxSuccessful()) {
                                throw failure(Kind.BAD_STATUS, "Policy LLM returned HTTP " + status);
                            }
                            byte[] body = input.readNBytes(MAX_HTTP_BYTES + 1);
                            if (body.length > MAX_HTTP_BYTES) {
                                throw failure(Kind.TRUNCATED, "Policy LLM response exceeded the byte limit");
                            }
                            return parse(readObject(body), maxTokens);
                        }
                    });
        } catch (RestClientException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                        || cause instanceof InterruptedException) {
                    throw failure(Kind.TIMEOUT, "Policy LLM request timed out or was interrupted");
                }
            }
            // Transport and JSON exceptions may contain URLs, headers or response excerpts. Do not propagate them.
            throw failure(Kind.BAD_STATUS, "Cannot read the policy LLM response");
        }
    }

    private LlmResponse parse(JsonNode root, int maxTokens) {
        JsonNode choices = root.path("choices");
        JsonNode choice = choices.path(0);
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asText("");
        if ("length".equals(finish)) {
            throw failure(Kind.TRUNCATED, "Policy LLM hit the output token limit");
        }
        if ("content_filter".equals(finish) || message.hasNonNull("refusal")) {
            throw failure(Kind.REFUSED, "Policy LLM refused to complete the request");
        }
        JsonNode content = message.path("content");
        if (!choices.isArray() || choices.size() != 1 || !"stop".equals(finish) || !content.isTextual()
                || message.hasNonNull("tool_calls") || message.hasNonNull("function_call")) {
            throw failure(Kind.INVALID_OUTPUT, "Policy LLM returned an invalid completion");
        }
        JsonNode usage = root.path("usage");
        JsonNode outputTokens = usage.path("completion_tokens");
        String text = content.textValue();
        if (text.length() > MAX_TEXT_CHARS || (outputTokens.isIntegralNumber()
                && (!outputTokens.canConvertToInt() || outputTokens.intValue() > maxTokens))) {
            throw failure(Kind.TRUNCATED, "Policy LLM answer exceeded the output limit");
        }
        readObject(text.getBytes(StandardCharsets.UTF_8));
        return new LlmResponse(text, tokens(usage.path("prompt_tokens")), tokens(outputTokens), "stop", cost(usage.path("cost")));
    }

    private JsonNode readObject(byte[] value) {
        try {
            JsonNode node = json.readTree(value);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (IOException e) {
            // JSON parse errors are deliberately replaced with a content-free message.
        }
        throw failure(Kind.INVALID_OUTPUT, "Policy LLM requires a single valid JSON object");
    }

    private static int tokens(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            int value = node.intValue();
            return value >= 0 && value <= MAX_USAGE_TOKENS ? value : 0;
        }
        return 0;
    }

    private static BigDecimal cost(JsonNode node) {
        if (node.isBigDecimal() || node.isIntegralNumber()) {
            BigDecimal amount = node.decimalValue();
            if (amount.signum() >= 0 && amount.scale() >= -6 && amount.scale() <= 12
                    && amount.precision() <= 18 && amount.compareTo(BigDecimal.valueOf(1_000)) <= 0) {
                return amount;
            }
        }
        return null;
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl != null) {
            try {
                URI base = URI.create(baseUrl);
                String host = base.getHost();
                if (host != null && ("https".equalsIgnoreCase(base.getScheme())
                        || ("http".equalsIgnoreCase(base.getScheme()) && LOOPBACK.contains(host)))
                        && base.getUserInfo() == null && base.getQuery() == null && base.getFragment() == null) {
                    return URI.create(baseUrl.replaceFirst("/$", "") + "/chat/completions");
                }
            } catch (IllegalArgumentException e) {
                // Do not propagate a URI error that might contain credentials.
            }
        }
        throw new IllegalArgumentException("Set a valid HTTPS listing-guard.analyzer.llm.base-url");
    }

    private static LlmException failure(Kind kind, String message) {
        return new LlmException(kind, message);
    }
}
