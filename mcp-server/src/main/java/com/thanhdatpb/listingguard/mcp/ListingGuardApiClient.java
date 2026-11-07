package com.thanhdatpb.listingguard.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only way this server talks to Listing Guard (ADR-017): HTTP calls to the REST API with the {@code X-API-Key}
 * header, nothing else. It follows no redirect (the key must not be sent to another host), reads at most
 * {@link #MAX_RESPONSE_BYTES} bytes, retries nothing and caches nothing. The key appears only in the header; if an
 * answer should ever echo it, it is replaced before anything else sees the answer.
 */
final class ListingGuardApiClient {

    /** The most bytes of one answer that are read; the API's own answers are far smaller. */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final String REDACTED = "<redacted>";

    private final HttpClient http;
    private final McpServerConfig config;

    ListingGuardApiClient(McpServerConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    ListingGuardApiClient(McpServerConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Sends the request and returns the API's answer, whatever its status.
     *
     * @param requestId the {@code X-Request-Id} to send (the API echoes it in its answer and its logs)
     * @throws ApiCallException when there is no usable answer: unreachable, timeout, too large
     */
    ApiResponse call(ApiRequest request, String requestId) throws ApiCallException {
        Endpoint endpoint = request.endpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uriFor(request))
                .timeout(config.timeout())
                .header("X-API-Key", config.apiKey())
                .header("X-Request-Id", requestId)
                .header("Accept", "application/json");
        if ("POST".equals(endpoint.method())) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.jsonBody(), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpRequest httpRequest = builder.build();

        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(httpRequest, info -> new LimitedBytes());
        HttpResponse<byte[]> response;
        try {
            // One deadline for the whole answer, body included (the request timeout only covers the headers).
            response = future.get(config.timeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiCallException(ApiCallException.TIMEOUT,
                    "The Listing Guard API did not answer within " + config.timeout().toSeconds() + " seconds.");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiCallException(ApiCallException.INTERNAL, "The call was interrupted.");
        } catch (ExecutionException e) {
            throw translate(e.getCause());
        }

        String body = new String(response.body(), StandardCharsets.UTF_8);
        return new ApiResponse(
                response.statusCode(),
                redact(body),
                redact(response.headers().firstValue("X-Request-Id").orElse(null)),
                redact(response.headers().firstValue("Retry-After").orElse(null)));
    }

    private ApiCallException translate(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLargeException) {
                return new ApiCallException(ApiCallException.INVALID_RESPONSE,
                        "The Listing Guard API answer is larger than " + (MAX_RESPONSE_BYTES / 1024)
                                + " KiB and was refused.");
            }
            if (t instanceof HttpTimeoutException) {
                return new ApiCallException(ApiCallException.TIMEOUT,
                        "The Listing Guard API did not answer within " + config.timeout().toSeconds() + " seconds.");
            }
        }
        if (cause instanceof IOException || cause instanceof IllegalArgumentException) {
            return new ApiCallException(ApiCallException.UNREACHABLE,
                    "The Listing Guard API could not be reached. Check " + McpServerConfig.BASE_URL
                            + " and that the API is running.");
        }
        return new ApiCallException(ApiCallException.INTERNAL, "The call to the Listing Guard API failed.");
    }

    /** Never throws for a value an agent can choose: the marketplace and the product type are percent-encoded. */
    URI uriFor(ApiRequest request) {
        Endpoint endpoint = request.endpoint();
        String path = endpoint.path();
        if (endpoint == Endpoint.RULESET_LIMITS) {
            path = path.replace("{marketplace}", percentEncode(request.marketplace(), false));
        }
        StringBuilder uri = new StringBuilder(config.baseUri().toString()).append(path);
        if (request.productType() != null && !request.productType().isEmpty()) {
            uri.append("?productType=").append(percentEncode(request.productType(), true));
        }
        return URI.create(uri.toString());
    }

    /**
     * Percent-encodes every byte of the UTF-8 form that is not a letter, a digit, {@code -} or {@code _} (and, in a query
     * value, {@code .} and {@code ~}). In a path segment a dot is encoded too, so {@code ..} can never mean "up".
     */
    static String percentEncode(String value, boolean query) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || (query && (c == '.' || c == '~'));
            if (plain) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** Raised when an answer is larger than {@link #MAX_RESPONSE_BYTES}; the rest of it is never read. */
    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("response too large", null);
        }
    }

    /** Collects the body up to {@link #MAX_RESPONSE_BYTES} and cancels the rest. */
    private static final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean finished;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (finished) {
                return;
            }
            for (ByteBuffer item : items) {
                int length = item.remaining();
                if (buffer.size() + length > MAX_RESPONSE_BYTES) {
                    finished = true;
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[length];
                item.get(chunk);
                buffer.write(chunk, 0, length);
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!finished) {
                finished = true;
                result.completeExceptionally(error);
            }
        }

        @Override
        public void onComplete() {
            if (!finished) {
                finished = true;
                result.complete(buffer.toByteArray());
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }

    private String redact(String text) {
        if (text == null || !text.contains(config.apiKey())) {
            return text;
        }
        return text.replace(config.apiKey(), REDACTED);
    }
}
