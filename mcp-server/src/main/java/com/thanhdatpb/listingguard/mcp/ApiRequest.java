package com.thanhdatpb.listingguard.mcp;

/**
 * One call to the Listing Guard API, described without a URL: the closed {@link Endpoint} plus the only three things an
 * agent can influence (ADR-017).
 *
 * @param endpoint    which call
 * @param marketplace the path parameter of {@link Endpoint#RULESET_LIMITS}; {@code null} for the others
 * @param productType the optional {@code productType} query parameter of {@link Endpoint#RULESET_LIMITS}
 * @param jsonBody    the request body of a {@code POST}, passed through as the agent gave it; {@code null} for a {@code GET}
 */
record ApiRequest(Endpoint endpoint, String marketplace, String productType, String jsonBody) {

    static ApiRequest post(Endpoint endpoint, String jsonBody) {
        return new ApiRequest(endpoint, null, null, jsonBody);
    }

    static ApiRequest limits(String marketplace, String productType) {
        return new ApiRequest(Endpoint.RULESET_LIMITS, marketplace, productType, null);
    }

    static ApiRequest usage() {
        return new ApiRequest(Endpoint.USAGE, null, null, null);
    }
}
