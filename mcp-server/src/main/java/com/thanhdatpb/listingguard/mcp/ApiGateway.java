package com.thanhdatpb.listingguard.mcp;

import com.thanhdatpb.listingguard.client.ListingGuardClient;
import com.thanhdatpb.listingguard.client.ListingGuardException;
import com.thanhdatpb.listingguard.client.ListingGuardTransportException;
import com.thanhdatpb.listingguard.client.RawRequest;
import com.thanhdatpb.listingguard.client.RawResponse;
import com.thanhdatpb.listingguard.client.RetryPolicy;

/**
 * The only way this server talks to Listing Guard (ADR-017, ADR-023): a raw call of the Java client SDK, which sends the
 * {@code X-API-Key} header and nothing else that carries it, follows no redirect, reads at most {@link #MAX_RESPONSE_BYTES}
 * bytes and replaces an echoed key before anything else sees the answer. It retries nothing ({@link RetryPolicy#none()}: the
 * agent sees the answer once and decides) and caches nothing. This class only turns the SDK's "no usable answer" into the
 * server's own codes.
 */
final class ApiGateway implements AutoCloseable {

    /** The most bytes of one answer that are read; the API's own answers are far smaller. */
    static final int MAX_RESPONSE_BYTES = ListingGuardClient.DEFAULT_MAX_RESPONSE_BYTES;

    private final ListingGuardClient client;
    private final McpServerConfig config;

    ApiGateway(McpServerConfig config) {
        this.config = config;
        this.client = ListingGuardClient.builder()
                .baseUrl(config.baseUri())
                .apiKey(config.apiKey())
                .timeout(config.timeout())
                .retry(RetryPolicy.none())
                .allowInsecureHttp(config.insecureHttpAllowed())
                .maxResponseBytes(MAX_RESPONSE_BYTES)
                .userAgent(ListingGuardMcpServer.SERVER_NAME + "/" + ListingGuardMcpServer.version())
                .build();
    }

    /**
     * Sends the request and returns the API's answer, whatever its status.
     *
     * @param requestId the {@code X-Request-Id} to send (the API echoes it in its answer and its logs)
     * @throws ApiCallException when there is no usable answer: unreachable, timeout, too large
     */
    RawResponse call(RawRequest request, String requestId) throws ApiCallException {
        try {
            return client.send(request.withRequestId(requestId));
        } catch (ListingGuardTransportException e) {
            throw translate(e);
        } catch (ListingGuardException | IllegalArgumentException e) {
            throw new ApiCallException(ApiCallException.INTERNAL, "The call to the Listing Guard API failed.");
        }
    }

    private ApiCallException translate(ListingGuardTransportException e) {
        return switch (e.kind()) {
            case TIMEOUT -> new ApiCallException(ApiCallException.TIMEOUT,
                    "The Listing Guard API did not answer within " + config.timeout().toSeconds() + " seconds.");
            case UNREACHABLE -> new ApiCallException(ApiCallException.UNREACHABLE,
                    "The Listing Guard API could not be reached. Check " + McpServerConfig.BASE_URL
                            + " and that the API is running.");
            case RESPONSE_TOO_LARGE -> new ApiCallException(ApiCallException.INVALID_RESPONSE,
                    "The Listing Guard API answer is larger than " + (MAX_RESPONSE_BYTES / 1024) + " KiB and was refused.");
            case REDIRECT, INVALID_RESPONSE -> new ApiCallException(ApiCallException.INVALID_RESPONSE,
                    "The Listing Guard API answer could not be used.");
            case INTERRUPTED -> new ApiCallException(ApiCallException.INTERNAL, "The call was interrupted.");
            case FAILED -> new ApiCallException(ApiCallException.INTERNAL, "The call to the Listing Guard API failed.");
        };
    }

    @Override
    public void close() {
        client.close();
    }
}
