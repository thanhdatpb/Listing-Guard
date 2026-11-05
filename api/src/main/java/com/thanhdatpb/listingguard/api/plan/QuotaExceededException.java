package com.thanhdatpb.listingguard.api.plan;

import java.time.Instant;

/** The monthly quota of the client's plan is used up (ADR-013). Answered as 429 {@code quota-exceeded}, with no Retry-After. */
public class QuotaExceededException extends RuntimeException {

    public static final String CODE = "quota-exceeded";

    private final String plan;
    private final int quota;
    private final long used;
    private final Instant resetAt;

    public QuotaExceededException(String plan, int quota, long used, Instant resetAt) {
        super("The monthly quota of the " + plan + " plan is used up (" + used + " of " + quota
                + "). It resets at " + resetAt + ". Results already stored are still answered from the cache.");
        this.plan = plan;
        this.quota = quota;
        this.used = used;
        this.resetAt = resetAt;
    }

    public String plan() {
        return plan;
    }

    public int quota() {
        return quota;
    }

    public long used() {
        return used;
    }

    public Instant resetAt() {
        return resetAt;
    }
}
