package com.thanhdatpb.listingguard.client;

/**
 * The API answered an operation with a {@code Deprecation} header (RFC 9745), perhaps with {@code Sunset} (RFC 8594) and a
 * {@code Link}. The values are passed on as sent (each at most 200 characters); the SDK does not interpret them.
 *
 * @param operation   the deprecated call
 * @param deprecation the {@code Deprecation} header
 * @param sunset      the {@code Sunset} header, or {@code null}
 * @param link        the {@code Link} header, or {@code null}
 * @param requestId   the request id of the answer, or {@code null}
 */
public record DeprecationNotice(Operation operation, String deprecation, String sunset, String link, String requestId) {
}
