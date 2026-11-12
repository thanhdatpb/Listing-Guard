package com.thanhdatpb.listingguard.client;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One call described without a URL: an {@link Operation} and the few values a caller can influence (ADR-023). Used with
 * {@link ListingGuardClient#send(RawRequest)} by a caller that must hand the API's answer on unchanged, such as the MCP server.
 * Immutable.
 */
public final class RawRequest {

    /** What the API accepts as {@code X-Request-Id} (ADR-005); anything else would be replaced by the server. */
    static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final Operation operation;
    private final String jsonBody;
    private final String marketplace;
    private final String productType;
    private final String requestId;
    private final String ifNoneMatch;

    private RawRequest(Operation operation, String jsonBody, String marketplace, String productType, String requestId,
            String ifNoneMatch) {
        this.operation = operation;
        this.jsonBody = jsonBody;
        this.marketplace = marketplace;
        this.productType = productType;
        this.requestId = requestId;
        this.ifNoneMatch = ifNoneMatch;
    }

    /**
     * A {@code POST} with a JSON body that is sent exactly as given.
     *
     * @param operation one of the four {@code POST} operations
     * @param jsonBody  the request body, a JSON object as text
     * @throws IllegalArgumentException when the operation is not a {@code POST}
     */
    public static RawRequest post(Operation operation, String jsonBody) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(jsonBody, "jsonBody");
        if (!operation.isPost()) {
            throw new IllegalArgumentException(operation + " is not a POST operation");
        }
        return new RawRequest(operation, jsonBody, null, null, null, null);
    }

    /**
     * {@code GET /api/v1/rulesets/{marketplace}/limits}.
     *
     * @param marketplace the marketplace code, percent-encoded into the path
     * @param productType optional {@code productType} query parameter; {@code null} or empty for none
     */
    public static RawRequest rulesetLimits(String marketplace, String productType) {
        Objects.requireNonNull(marketplace, "marketplace");
        return new RawRequest(Operation.RULESET_LIMITS, null, marketplace,
                productType == null || productType.isEmpty() ? null : productType, null, null);
    }

    /** {@code GET /api/v1/usage}. */
    public static RawRequest usage() {
        return new RawRequest(Operation.USAGE, null, null, null, null, null);
    }

    /** {@code GET /api/v1/capabilities}. */
    public static RawRequest capabilities() {
        return new RawRequest(Operation.CAPABILITIES, null, null, null, null, null);
    }

    /**
     * The same request with an {@code X-Request-Id} of the caller's choosing instead of a generated one.
     *
     * @param id 1 to 64 characters from {@code A-Za-z0-9._-}
     * @throws IllegalArgumentException for any other value
     */
    public RawRequest withRequestId(String id) {
        if (id == null || !REQUEST_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A request id is 1 to 64 characters from A-Za-z0-9._-");
        }
        return new RawRequest(operation, jsonBody, marketplace, productType, id, ifNoneMatch);
    }

    /**
     * The same {@code GET} with an {@code If-None-Match} header; the API answers {@code 304} when the value matches its
     * {@code ETag}. Only {@link Operation#CAPABILITIES} and {@link Operation#RULESET_LIMITS} carry an {@code ETag}.
     *
     * @param etag the {@code ETag} of an earlier answer, exactly as received
     */
    public RawRequest withIfNoneMatch(String etag) {
        if (operation.isPost()) {
            throw new IllegalArgumentException("If-None-Match applies to a GET only");
        }
        return new RawRequest(operation, jsonBody, marketplace, productType, requestId,
                etag == null || etag.isEmpty() ? null : etag);
    }

    /** Which call. */
    public Operation operation() {
        return operation;
    }

    /** The body of a {@code POST}, or {@code null}. */
    public String jsonBody() {
        return jsonBody;
    }

    /** The marketplace of {@link Operation#RULESET_LIMITS}, or {@code null}. */
    public String marketplace() {
        return marketplace;
    }

    /** The {@code productType} query parameter, or {@code null}. */
    public String productType() {
        return productType;
    }

    /** The caller's request id, or {@code null} for a generated one. */
    public String requestId() {
        return requestId;
    }

    /** The {@code If-None-Match} value, or {@code null}. */
    public String ifNoneMatch() {
        return ifNoneMatch;
    }

    /** Never shows the body: it is a listing or a buyer's text. */
    @Override
    public String toString() {
        return "RawRequest[operation=" + operation + (jsonBody == null ? "" : ", body=<hidden>")
                + (requestId == null ? "" : ", requestId=" + requestId) + "]";
    }
}
