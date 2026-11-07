package com.thanhdatpb.listingguard.mcp;

/**
 * What the Listing Guard API answered, before any interpretation.
 *
 * @param status           the HTTP status
 * @param body             the response body, decoded as UTF-8 (at most {@link ListingGuardApiClient#MAX_RESPONSE_BYTES} bytes)
 * @param requestIdHeader  the {@code X-Request-Id} response header, or {@code null}
 * @param retryAfterHeader the {@code Retry-After} response header, or {@code null}
 */
record ApiResponse(int status, String body, String requestIdHeader, String retryAfterHeader) {
}
