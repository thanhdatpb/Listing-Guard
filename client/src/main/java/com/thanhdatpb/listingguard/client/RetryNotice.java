package com.thanhdatpb.listingguard.client;

import java.time.Duration;

/**
 * The SDK is about to retry a call.
 *
 * @param operation   the call
 * @param nextAttempt the number of the attempt about to start (2 for the first retry)
 * @param status      the HTTP status of the answer that is retried ({@code 429} or {@code 503})
 * @param code        the problem code of that answer, or {@code null} when it was not a Listing Guard problem
 * @param delay       how long the SDK waits before the attempt
 * @param requestId   the {@code X-Request-Id} every attempt of this call carries
 */
public record RetryNotice(Operation operation, int nextAttempt, int status, String code, Duration delay, String requestId) {
}
