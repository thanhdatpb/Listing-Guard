package com.thanhdatpb.listingguard.client;

/**
 * The version the API reported in {@code X-Listing-Guard-Version}, next to this SDK's own. The SDK decides nothing from it:
 * what the value means is the API contract's (ADR-022) to define.
 *
 * @param serverVersion the header value as sent (at most 200 characters)
 * @param sdkVersion    the version of this SDK
 * @param operation     the call whose answer carried it
 */
public record ServerVersionNotice(String serverVersion, String sdkVersion, Operation operation) {
}
