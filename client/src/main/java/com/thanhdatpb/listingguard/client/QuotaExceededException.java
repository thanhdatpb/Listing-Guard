package com.thanhdatpb.listingguard.client;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * {@code 429 quota-exceeded} (ADR-013): the month's quota of the client's plan is used up. <b>Do not retry before
 * {@link #resetAt()} or a plan change</b>; the SDK never retries it, even when a proxy added {@code Retry-After}. Results the
 * client already has are still answered from the cache.
 */
public class QuotaExceededException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    public QuotaExceededException(ApiProblem problem) {
        super(problem);
    }

    /** The client's plan ({@code FREE}, {@code SELLER}, {@code PRO}, ...), or {@code null}. */
    public String plan() {
        return problem().text("plan");
    }

    /** The monthly quota, or {@code null}. */
    public Long quota() {
        return problem().number("quota");
    }

    /** The units used this month, or {@code null}. */
    public Long used() {
        return problem().number("used");
    }

    /** When the quota starts again (UTC), or {@code null} when the answer did not say or said it unreadably. */
    public Instant resetAt() {
        String text = problem().text("resetAt");
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
