package com.thanhdatpb.listingguard.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.ListingGuardTransportException.Kind;
import com.thanhdatpb.listingguard.client.model.Capabilities;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.CheckResult;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningResult;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckResult;
import com.thanhdatpb.listingguard.client.model.Usage;
import com.thanhdatpb.listingguard.client.model.VariationCheckRequest;
import com.thanhdatpb.listingguard.client.model.VariationCheckResult;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The Listing Guard API client (ADR-023). Build one with {@link #builder()}, keep it and share it: it is immutable,
 * thread-safe and reuses one {@code HttpClient} for every call. Close it when the application stops.
 *
 * <pre>{@code
 * ListingGuardClient client = ListingGuardClient.builder()
 *     .baseUrl("https://listing-api.example.com")
 *     .apiKey(System.getenv("LISTING_GUARD_API_KEY"))
 *     .build();
 * CheckResult result = client.checks(CheckRequest.of("ETSY", Map.of("title", "Handmade mug", "tags", List.of("mug"))));
 * }</pre>
 *
 * <p>Every call checks; nothing edits a listing, applies a fix or publishes. Each method has a blocking form, which throws a
 * {@link ListingGuardException}, and an {@code ...Async} form, whose future completes exceptionally with the same exception
 * ({@code join()} wraps it in a {@link CompletionException}, {@code get()} in an {@link ExecutionException}). The results
 * are the API's: an {@code INCOMPLETE} result is not a clean one and an {@code UNKNOWN} risk is not {@code LOW}.
 */
public final class ListingGuardClient implements AutoCloseable {

    /** The default answer size limit (2 MiB, as in the MCP server). */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** The default timeout of one attempt (connection, headers and body together). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpTransport transport;
    private final Retrier retrier;
    private final ObjectMapper mapper;
    private final Supplier<String> requestIds;
    private final Clock clock;
    private final URI baseUri;

    private ListingGuardClient(Builder b, URI baseUri) {
        this.baseUri = baseUri;
        this.mapper = Json.newMapper();
        this.clock = b.clock;
        this.requestIds = b.requestIdSupplier;
        String agent = (b.userAgent == null ? "" : b.userAgent + " ") + "listing-guard-client/" + SdkVersion.VALUE
                + " (Java/" + Runtime.version().feature() + ")";
        Map<String, String> extra = new LinkedHashMap<>();
        List<String> extraSecrets = List.of();
        if (b.accessClientId != null) {
            extra.put("CF-Access-Client-Id", b.accessClientId);
            extra.put("CF-Access-Client-Secret", b.accessClientSecret);
            extraSecrets = List.of(b.accessClientSecret);
        }
        this.transport = new HttpTransport(baseUri, b.apiKey, b.timeout, b.maxResponseBytes, agent, extra, extraSecrets);
        this.retrier = new Retrier(transport, b.retry, new Notices(b.listener), mapper, b.delayer, clock);
    }

    /** A new builder. {@code baseUrl} and {@code apiKey} are required. */
    public static Builder builder() {
        return new Builder();
    }

    /** The version of this SDK, as in its {@code User-Agent}. */
    public static String sdkVersion() {
        return SdkVersion.VALUE;
    }

    // ---- checks -------------------------------------------------------------------------------------------------------

    /** {@code POST /api/v1/checks}: the marketplace's rules. A new result may use one unit of the monthly quota. */
    public CheckResult checks(CheckRequest request) {
        return await(checksAsync(request));
    }

    /** The asynchronous form of {@link #checks(CheckRequest)}. */
    public CompletableFuture<CheckResult> checksAsync(CheckRequest request) {
        return post(Operation.CHECKS, request, CheckResult.class);
    }

    /**
     * {@code POST /api/v1/trademark-checks}, with the optional {@code product_type}. A reference warning, not a legal
     * conclusion: show its {@code disclaimer}. When the deployment has no trademark catalog this throws
     * {@link NotConfiguredException}: the risk is unknown, not low. An undeclared product type throws
     * {@link UnknownProductTypeException}.
     */
    public TrademarkCheckResult trademarkChecks(TrademarkCheckRequest request) {
        return await(trademarkChecksAsync(request));
    }

    /** The asynchronous form of {@link #trademarkChecks(TrademarkCheckRequest)}. */
    public CompletableFuture<TrademarkCheckResult> trademarkChecksAsync(TrademarkCheckRequest request) {
        return post(Operation.TRADEMARK_CHECKS, request, TrademarkCheckResult.class);
    }

    /** {@code POST /api/v1/variation-checks}: the structure of a parent and its children. */
    public VariationCheckResult variationChecks(VariationCheckRequest request) {
        return await(variationChecksAsync(request));
    }

    /** The asynchronous form of {@link #variationChecks(VariationCheckRequest)}. */
    public CompletableFuture<VariationCheckResult> variationChecksAsync(VariationCheckRequest request) {
        return post(Operation.VARIATION_CHECKS, request, VariationCheckResult.class);
    }

    /**
     * {@code POST /api/v1/personalization-screenings}: the text a buyer typed, sent exactly as typed (it is personal data; the
     * SDK never logs it).
     */
    public PersonalizationScreeningResult personalizationScreenings(PersonalizationScreeningRequest request) {
        return await(personalizationScreeningsAsync(request));
    }

    /** The asynchronous form of {@link #personalizationScreenings(PersonalizationScreeningRequest)}. */
    public CompletableFuture<PersonalizationScreeningResult> personalizationScreeningsAsync(
            PersonalizationScreeningRequest request) {
        return post(Operation.PERSONALIZATION_SCREENINGS, request, PersonalizationScreeningResult.class);
    }

    // ---- readers ------------------------------------------------------------------------------------------------------

    /** {@code GET /api/v1/rulesets/{marketplace}/limits}: the numeric limits of the active listing rules. Costs no quota. */
    public RulesetLimits rulesetLimits(String marketplace) {
        return rulesetLimits(marketplace, null);
    }

    /** The same, with the {@code productType} whose overrides apply (Amazon). */
    public RulesetLimits rulesetLimits(String marketplace, String productType) {
        return await(rulesetLimitsAsync(marketplace, productType));
    }

    /**
     * The same as a conditional request: {@code 304} when {@code ifNoneMatch} is the {@code ETag} of the current answer.
     *
     * @param ifNoneMatch the {@code ETag} of an earlier answer, or {@code null} for a plain request
     */
    public Conditional<RulesetLimits> rulesetLimits(String marketplace, String productType, String ifNoneMatch) {
        return await(rulesetLimitsAsync(marketplace, productType, ifNoneMatch));
    }

    /** The asynchronous form of {@link #rulesetLimits(String, String)}. */
    public CompletableFuture<RulesetLimits> rulesetLimitsAsync(String marketplace, String productType) {
        return typed(RawRequest.rulesetLimits(Objects.requireNonNull(marketplace, "marketplace"), productType),
                RulesetLimits.class);
    }

    /** The asynchronous form of {@link #rulesetLimits(String, String, String)}. */
    public CompletableFuture<Conditional<RulesetLimits>> rulesetLimitsAsync(String marketplace, String productType,
            String ifNoneMatch) {
        RawRequest request = RawRequest.rulesetLimits(Objects.requireNonNull(marketplace, "marketplace"), productType)
                .withIfNoneMatch(ifNoneMatch);
        return conditional(request, RulesetLimits.class);
    }

    /** {@code GET /api/v1/capabilities}: what the deployment serves, the same for every client. Costs no quota. */
    public Capabilities capabilities() {
        return await(capabilitiesAsync());
    }

    /**
     * The same as a conditional request: {@code 304} ("not modified") when {@code ifNoneMatch} is the {@code ETag} of the
     * current answer.
     *
     * @param ifNoneMatch the {@code ETag} of an earlier answer, or {@code null} for a plain request
     */
    public Conditional<Capabilities> capabilities(String ifNoneMatch) {
        return await(capabilitiesAsync(ifNoneMatch));
    }

    /** The asynchronous form of {@link #capabilities()}. */
    public CompletableFuture<Capabilities> capabilitiesAsync() {
        return typed(RawRequest.capabilities(), Capabilities.class);
    }

    /** The asynchronous form of {@link #capabilities(String)}. */
    public CompletableFuture<Conditional<Capabilities>> capabilitiesAsync(String ifNoneMatch) {
        return conditional(RawRequest.capabilities().withIfNoneMatch(ifNoneMatch), Capabilities.class);
    }

    /** {@code GET /api/v1/usage}: the calling client's plan, packs, quota and usage. Costs no quota. */
    public Usage usage() {
        return await(usageAsync());
    }

    /** The asynchronous form of {@link #usage()}. */
    public CompletableFuture<Usage> usageAsync() {
        return typed(RawRequest.usage(), Usage.class);
    }

    // ---- raw ----------------------------------------------------------------------------------------------------------

    /**
     * Sends a raw request and returns the answer whatever its status, without reading it as a type and without throwing for a
     * {@code 4xx} or {@code 5xx} (ADR-023). The same transport, safety rules and retry policy as the typed methods apply. For
     * callers that must hand the answer on unchanged, such as the MCP server.
     *
     * @throws ListingGuardTransportException when there is no answer at all
     */
    public RawResponse send(RawRequest request) {
        return await(sendAsync(request));
    }

    /** The asynchronous form of {@link #send(RawRequest)}. */
    public CompletableFuture<RawResponse> sendAsync(RawRequest request) {
        Objects.requireNonNull(request, "request");
        return expose(retrier.execute(request, requestId(request)));
    }

    /** Closes the underlying {@code HttpClient}; calls in flight are allowed to finish. */
    @Override
    public void close() {
        transport.close();
    }

    @Override
    public String toString() {
        return "ListingGuardClient[baseUrl=" + baseUri + ", sdk=" + SdkVersion.VALUE + ", apiKey=<hidden>]";
    }

    // ---- internals ----------------------------------------------------------------------------------------------------

    private <T> CompletableFuture<T> post(Operation operation, Object request, Class<T> type) {
        Objects.requireNonNull(request, "request");
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Only the class: a message could quote part of the listing.
            return CompletableFuture.failedFuture(new ListingGuardTransportException(Kind.FAILED,
                    "The request could not be written as JSON (" + e.getClass().getSimpleName() + ").", null));
        }
        return typed(RawRequest.post(operation, body), type);
    }

    private <T> CompletableFuture<T> typed(RawRequest request, Class<T> type) {
        return expose(retrier.execute(request, requestId(request)).thenApply(response -> {
            if (response.status() == 304) {
                throw new ListingGuardTransportException(Kind.INVALID_RESPONSE,
                        "The API answered 304 to a request that was not conditional.", null);
            }
            return read(response, type);
        }));
    }

    private <T> CompletableFuture<Conditional<T>> conditional(RawRequest request, Class<T> type) {
        return expose(retrier.execute(request, requestId(request)).thenApply(response -> {
            if (response.status() == 304) {
                String etag = response.etag() != null ? response.etag() : request.ifNoneMatch();
                return Conditional.<T>unchanged(etag);
            }
            return Conditional.modified(read(response, type), response.etag());
        }));
    }

    private <T> T read(RawResponse response, Class<T> type) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            JsonNode root;
            try {
                root = mapper.readTree(response.body());
            } catch (JsonProcessingException e) {
                root = null;
            }
            if (root == null || !root.isObject()) {
                throw new ListingGuardTransportException(Kind.INVALID_RESPONSE, "The API answered HTTP " + status
                        + " but the body is not a JSON object. Check the base URL.", null);
            }
            try {
                return mapper.treeToValue(root, type);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new ListingGuardTransportException(Kind.INVALID_RESPONSE, "The answer of the API could not be read as "
                        + type.getSimpleName() + ".", null);
            }
        }
        if (status >= 300 && status < 400) {
            throw new ListingGuardTransportException(Kind.REDIRECT, "The API answered with a redirect (HTTP " + status
                    + "). Redirects are not followed, so the key is never sent to another host; configure the final URL.",
                    null);
        }
        throw Problems.exception(mapper, response, clock.instant());
    }

    private String requestId(RawRequest request) {
        if (request.requestId() != null) {
            return request.requestId();
        }
        if (requestIds != null) {
            String id = null;
            try {
                id = requestIds.get();
            } catch (RuntimeException ignored) {
                // a failing supplier falls back to a generated id
            }
            if (id != null && RawRequest.REQUEST_ID.matcher(id).matches()) {
                return id;
            }
        }
        return UUID.randomUUID().toString();
    }

    /** The future handed to a caller: it completes with the bare {@link ListingGuardException}, never a wrapper. */
    private static <T> CompletableFuture<T> expose(CompletableFuture<T> internal) {
        CompletableFuture<T> out = new CompletableFuture<>();
        internal.whenComplete((value, error) -> {
            if (error == null) {
                out.complete(value);
            } else {
                out.completeExceptionally(unwrap(error));
            }
        });
        return out;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof ListingGuardException) {
            return t;
        }
        if (t instanceof CancellationException) {
            return new ListingGuardTransportException(Kind.INTERRUPTED, "The call was cancelled.", null);
        }
        return new ListingGuardTransportException(Kind.FAILED, "The call to the Listing Guard API failed.", t);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ListingGuardTransportException(Kind.INTERRUPTED, "The call was interrupted.", null);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof ListingGuardException lg) {
                throw lg;
            }
            throw new ListingGuardTransportException(Kind.FAILED, "The call to the Listing Guard API failed.", cause);
        } catch (CancellationException e) {
            throw new ListingGuardTransportException(Kind.INTERRUPTED, "The call was cancelled.", null);
        }
    }

    /**
     * Builds a {@link ListingGuardClient}. {@code baseUrl} and {@code apiKey} are required; everything else has a default. Not
     * thread-safe; the client it builds is.
     */
    public static final class Builder {

        /** Visible ASCII only: these values go into header values, and a space or a control character must never get there. */
        private static final Pattern TOKEN = Pattern.compile("^[\\x21-\\x7E]+$");
        private static final Pattern USER_AGENT = Pattern.compile("^[\\x20-\\x7E]{1,200}$");

        private String baseUrl;
        private String apiKey;
        private Duration timeout = DEFAULT_TIMEOUT;
        private RetryPolicy retry = RetryPolicy.defaults();
        private String userAgent;
        private String accessClientId;
        private String accessClientSecret;
        private ListingGuardListener listener;
        private boolean allowInsecureHttp;
        private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private Supplier<String> requestIdSupplier;
        private Retrier.Delayer delayer = Retrier.Delayer.DEFAULT;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * The API's base URL, for example {@code https://listing-api.example.com} (a path prefix is kept). {@code https} is
         * required unless the host is {@code localhost} or a loopback address, or {@link #allowInsecureHttp(boolean)} is set.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** The same, as a {@link URI}. */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl == null ? null : baseUrl.toString();
            return this;
        }

        /** The API key of one client, sent only in {@code X-API-Key}. Never logged, never in a message or {@code toString}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The timeout of one attempt, connection, headers and body together (default 10 s; 1 ms to 10 min). */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("timeout must be more than zero and at most 10 minutes");
            }
            this.timeout = timeout;
            return this;
        }

        /** When to retry (default {@link RetryPolicy#defaults()}; {@link RetryPolicy#none()} for one attempt). */
        public Builder retry(RetryPolicy retry) {
            this.retry = Objects.requireNonNull(retry, "retry");
            return this;
        }

        /**
         * Your application's product token, for example {@code shop-backend/1.4}, put in front of the SDK's own in the
         * {@code User-Agent} header (printable ASCII, at most 200 characters).
         */
        public Builder userAgent(String userAgent) {
            if (userAgent != null && !USER_AGENT.matcher(userAgent).matches()) {
                throw new IllegalArgumentException("userAgent must be 1 to 200 printable ASCII characters");
            }
            this.userAgent = userAgent;
            return this;
        }

        /**
         * The Cloudflare Access service token of a deployment behind Access: sent as {@code CF-Access-Client-Id} and
         * {@code CF-Access-Client-Secret} on every request. Both are secrets like the key.
         */
        public Builder cloudflareAccess(String clientId, String clientSecret) {
            if (clientId == null || clientSecret == null || !TOKEN.matcher(clientId).matches()
                    || !TOKEN.matcher(clientSecret).matches()) {
                throw new IllegalArgumentException("cloudflareAccess needs a client id and a client secret of visible ASCII "
                        + "characters");
            }
            this.accessClientId = clientId;
            this.accessClientSecret = clientSecret;
            return this;
        }

        /** Optional callbacks (server version, deprecation, retries). */
        public Builder listener(ListingGuardListener listener) {
            this.listener = listener;
            return this;
        }

        /** Accept {@code http} to a host that is not loopback (a trusted private network). Off by default. */
        public Builder allowInsecureHttp(boolean allowInsecureHttp) {
            this.allowInsecureHttp = allowInsecureHttp;
            return this;
        }

        /** The largest answer read, in bytes (default 2 MiB; 1 KiB to 64 MiB). */
        public Builder maxResponseBytes(int maxResponseBytes) {
            if (maxResponseBytes < 1024 || maxResponseBytes > 64 * 1024 * 1024) {
                throw new IllegalArgumentException("maxResponseBytes must be between 1 KiB and 64 MiB");
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /**
         * Where the {@code X-Request-Id} of a call comes from, for example the caller's trace id. A value that is not 1 to 64
         * characters from {@code A-Za-z0-9._-} (or {@code null}) is replaced by a generated UUID. The default is a UUID per
         * call. All attempts of one call carry the same id.
         */
        public Builder requestIdSupplier(Supplier<String> requestIdSupplier) {
            this.requestIdSupplier = requestIdSupplier;
            return this;
        }

        Builder delayer(Retrier.Delayer delayer) {
            this.delayer = Objects.requireNonNull(delayer);
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /**
         * Builds the client.
         *
         * @throws IllegalArgumentException when the base URL or the key is missing or not acceptable (the message never
         *                                  repeats a value)
         */
        public ListingGuardClient build() {
            URI uri = BaseUrls.parse(baseUrl, allowInsecureHttp);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            if (!TOKEN.matcher(apiKey.strip()).matches()) {
                throw new IllegalArgumentException("apiKey contains characters that cannot be sent in an HTTP header (only "
                        + "visible ASCII characters, no spaces)");
            }
            apiKey = apiKey.strip();
            return new ListingGuardClient(this, uri);
        }

        @Override
        public String toString() {
            return "ListingGuardClient.Builder[timeout=" + timeout + ", retry=" + retry + ", apiKey="
                    + (apiKey == null ? "<unset>" : "<hidden>") + "]";
        }
    }
}
