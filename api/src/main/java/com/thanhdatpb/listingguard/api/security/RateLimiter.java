package com.thanhdatpb.listingguard.api.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One token bucket per API key name. The clock is injected so tests do not have to wait. */
public final class RateLimiter {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Holds up to {@code capacity} tokens, refilled continuously; a request costs one token. */
    static final class TokenBucket {

        private final double capacity;
        private final double refillPerNano;
        private final LongSupplier clock;
        private double tokens;
        private long last;

        TokenBucket(int capacity, double refillPerSecond, LongSupplier clock) {
            this.capacity = capacity;
            this.refillPerNano = refillPerSecond / NANOS_PER_SECOND;
            this.clock = clock;
            this.tokens = capacity;
            this.last = clock.getAsLong();
        }

        /** @return 0 when the request may go ahead, otherwise how many nanoseconds until a token is available */
        synchronized long tryAcquire() {
            long now = clock.getAsLong();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
            last = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            return (long) Math.ceil((1.0 - tokens) / refillPerNano);
        }
    }

    private final int burst;
    private final double refillPerSecond;
    private final LongSupplier clock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** With an explicit clock (nanoseconds), for tests that must not wait in real time. */
    public RateLimiter(RateLimitProperties properties, LongSupplier clock) {
        this.burst = properties.burst();
        this.refillPerSecond = properties.requestsPerMinute() / 60.0;
        this.clock = clock;
    }

    /** @return 0 when allowed, otherwise the nanoseconds the caller has to wait */
    public long acquire(String key) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(burst, refillPerSecond, clock)).tryAcquire();
    }
}
