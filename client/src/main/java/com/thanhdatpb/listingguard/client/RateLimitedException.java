package com.thanhdatpb.listingguard.client;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code 429 rate-limited} (ADR-005): too many requests for the calling client. The SDK retries it within its
 * {@link RetryPolicy}; this exception means the retries ran out or the wait the API asked for was longer than the policy
 * allows. Retry after {@link #retryAfter()}.
 */
public class RateLimitedException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    /**
     * @param problem    the problem
     * @param retryAfter the wait from the {@code Retry-After} header, or {@code null} when there was none
     */
    public RateLimitedException(ApiProblem problem, Duration retryAfter) {
        super(problem);
        this.retryAfter = retryAfter;
    }

    /** The wait the API asked for in {@code Retry-After}, when it gave one. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
