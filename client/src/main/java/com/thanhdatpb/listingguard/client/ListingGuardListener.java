package com.thanhdatpb.listingguard.client;

/**
 * Optional callbacks for what a caller may want to log or alert on (ADR-023). The SDK logs nothing itself. Every method has
 * an empty default, so implement only what you need. A notice never carries a request or response body, the API key, a
 * Cloudflare Access secret or listing text.
 *
 * <p>Methods are called on the thread that completes the HTTP exchange, so keep them short and do not block. An exception a
 * method throws is caught and ignored: a listener can never break a call.
 */
public interface ListingGuardListener {

    /**
     * The API sent an {@code X-Listing-Guard-Version} header. Called once per distinct value a client instance sees (at most
     * 64), so a deployment that changes version mid-flight is reported again.
     */
    default void onServerVersion(ServerVersionNotice notice) {
    }

    /**
     * The API marked an operation as deprecated with a {@code Deprecation} header. Called once per operation and header value.
     * Plan the move before the date in {@link DeprecationNotice#sunset()}, when there is one.
     */
    default void onDeprecation(DeprecationNotice notice) {
    }

    /** The SDK is about to retry a call (a {@code 429 rate-limited} or a {@code 503}) after a wait. */
    default void onRetry(RetryNotice notice) {
    }
}
