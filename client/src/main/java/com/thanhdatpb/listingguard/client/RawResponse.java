package com.thanhdatpb.listingguard.client;

import java.net.http.HttpHeaders;
import java.util.Objects;

/**
 * What the API answered, before any interpretation: the status, the body as text and the headers. A secret the SDK holds
 * (the API key, a Cloudflare Access secret) that an answer echoes is already replaced with {@code <redacted>}.
 */
public final class RawResponse {

    private final int status;
    private final String body;
    private final HttpHeaders headers;

    /**
     * @param status  the HTTP status
     * @param body    the body decoded as UTF-8, empty when there is none
     * @param headers the response headers
     */
    public RawResponse(int status, String body, HttpHeaders headers) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The body decoded as UTF-8, exactly as received apart from redaction; empty when there is none. */
    public String body() {
        return body;
    }

    /** All response headers (names are case-insensitive). */
    public HttpHeaders headers() {
        return headers;
    }

    /** The first value of a header, or {@code null}. */
    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    /** {@code X-Request-Id}, or {@code null}. */
    public String requestId() {
        return header("X-Request-Id");
    }

    /** {@code Retry-After} as sent, or {@code null}. */
    public String retryAfter() {
        return header("Retry-After");
    }

    /** {@code ETag} as sent, or {@code null}. */
    public String etag() {
        return header("ETag");
    }

    /** True for a {@code 2xx}. */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** Never shows the body. */
    @Override
    public String toString() {
        return "RawResponse[status=" + status + ", bytes=" + body.length() + "]";
    }
}
