package com.thanhdatpb.listingguard.client;

import com.thanhdatpb.listingguard.client.ListingGuardTransportException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One attempt of one call over the JDK {@code HttpClient} (ADR-023): the secrets only in their headers, no redirect followed,
 * at most {@code maxResponseBytes} read, one deadline for headers and body, and every secret an answer echoes replaced with
 * {@code <redacted>} before anything else sees the answer. It does not retry and does not interpret the status.
 */
final class HttpTransport implements AutoCloseable {

    static final String REDACTED = "<redacted>";

    private final HttpClient http;
    private final URI baseUri;
    private final String apiKey;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final String userAgent;
    private final Map<String, String> extraHeaders;
    private final List<String> secrets;

    HttpTransport(URI baseUri, String apiKey, Duration timeout, int maxResponseBytes, String userAgent,
            Map<String, String> extraHeaders, List<String> extraSecrets) {
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.userAgent = userAgent;
        this.extraHeaders = Map.copyOf(extraHeaders);
        List<String> all = new ArrayList<>();
        all.add(apiKey);
        all.addAll(extraSecrets);
        this.secrets = List.copyOf(all);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Sends the request once. Completes with the answer whatever its status, or with a {@link ListingGuardTransportException}. */
    CompletableFuture<RawResponse> exchange(RawRequest request, String requestId) {
        HttpRequest httpRequest;
        try {
            httpRequest = build(request, requestId);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new ListingGuardTransportException(Kind.FAILED,
                    "The request could not be built (a header or the URL is not valid).", null));
        }
        CompletableFuture<HttpResponse<byte[]>> sent = http.sendAsync(httpRequest, info -> new LimitedBytes(maxResponseBytes));
        CompletableFuture<HttpResponse<byte[]>> bounded = new CompletableFuture<>();
        sent.whenComplete((response, error) -> {
            if (error != null) {
                bounded.completeExceptionally(error);
            } else {
                bounded.complete(response);
            }
        });
        // One deadline for the whole answer, body included (the request timeout only covers the headers).
        bounded.orTimeout(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
            if (error != null) {
                sent.cancel(true);
            }
        });
        CompletableFuture<RawResponse> result = new CompletableFuture<>();
        bounded.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(translate(error));
                return;
            }
            try {
                result.complete(toRaw(response));
            } catch (RuntimeException e) {
                result.completeExceptionally(new ListingGuardTransportException(Kind.FAILED,
                        "The answer of the Listing Guard API could not be read.", e));
            }
        });
        return result;
    }

    URI uriFor(RawRequest request) {
        Operation operation = request.operation();
        String path = operation.path();
        if (operation == Operation.RULESET_LIMITS) {
            path = path.replace("{marketplace}", percentEncode(request.marketplace(), false));
        }
        StringBuilder uri = new StringBuilder(baseUri.toString()).append(path);
        if (request.productType() != null && !request.productType().isEmpty()) {
            uri.append("?productType=").append(percentEncode(request.productType(), true));
        }
        return URI.create(uri.toString());
    }

    private HttpRequest build(RawRequest request, String requestId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uriFor(request))
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .header("X-Request-Id", requestId)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
        extraHeaders.forEach(builder::header);
        if (request.ifNoneMatch() != null) {
            builder.header("If-None-Match", request.ifNoneMatch());
        }
        if (request.operation().isPost()) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.jsonBody(), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private RawResponse toRaw(HttpResponse<byte[]> response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, values.stream().map(this::redact).toList()));
        return new RawResponse(response.statusCode(), redact(body), HttpHeaders.of(headers, (a, b) -> true));
    }

    private ListingGuardTransportException translate(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLargeException) {
                return new ListingGuardTransportException(Kind.RESPONSE_TOO_LARGE, "The Listing Guard API answer is larger "
                        + "than " + (maxResponseBytes / 1024) + " KiB and was refused.", null);
            }
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                return new ListingGuardTransportException(Kind.TIMEOUT, "The Listing Guard API did not answer within "
                        + timeout.toMillis() + " ms.", null);
            }
        }
        if (cause instanceof IOException || cause instanceof IllegalArgumentException) {
            return new ListingGuardTransportException(Kind.UNREACHABLE, "The Listing Guard API could not be reached. Check "
                    + "the base URL and that the API is running.", cause);
        }
        if (cause instanceof java.util.concurrent.CancellationException) {
            return new ListingGuardTransportException(Kind.INTERRUPTED, "The call was cancelled.", null);
        }
        return new ListingGuardTransportException(Kind.FAILED, "The call to the Listing Guard API failed.", cause);
    }

    /** Replaces every secret this client holds; an answer can then never hand one back to a caller. */
    String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String secret : secrets) {
            if (!secret.isEmpty() && out.contains(secret)) {
                out = out.replace(secret, REDACTED);
            }
        }
        return out;
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

    @Override
    public void close() {
        http.close();
    }

    /** Raised when an answer is larger than the limit; the rest of it is never read. */
    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("response too large", null);
        }
    }

    /** Collects the body up to the limit and cancels the rest. */
    private static final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean finished;

        LimitedBytes(int limit) {
            this.limit = limit;
        }

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
                if (buffer.size() + length > limit) {
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
}
