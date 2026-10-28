package com.thanhdatpb.listingguard.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final long[] nanos = {0};

    private RateLimiter limiter(int requestsPerMinute, int burst) {
        return new RateLimiter(new RateLimitProperties(true, requestsPerMinute, burst), () -> nanos[0]);
    }

    private void advanceSeconds(double seconds) {
        nanos[0] += (long) (seconds * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void allowsABurstThenBlocks() {
        RateLimiter limiter = limiter(60, 20);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.acquire("preview")).as("request %d", i + 1).isZero();
        }
        assertThat(limiter.acquire("preview")).isPositive();
    }

    @Test
    void refillsAtTheConfiguredRate() {
        RateLimiter limiter = limiter(60, 2);
        limiter.acquire("k");
        limiter.acquire("k");

        long wait = limiter.acquire("k");
        assertThat(TimeUnit.NANOSECONDS.toMillis(wait)).as("one token per second at 60 per minute").isBetween(990L, 1010L);

        advanceSeconds(0.5);
        assertThat(limiter.acquire("k")).isPositive();
        advanceSeconds(0.6);
        assertThat(limiter.acquire("k")).isZero();
    }

    @Test
    void neverRefillsBeyondTheBurst() {
        RateLimiter limiter = limiter(60, 3);

        advanceSeconds(3600);

        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isPositive();
    }

    @Test
    void eachKeyHasItsOwnBucket() {
        RateLimiter limiter = limiter(60, 1);

        assertThat(limiter.acquire("one")).isZero();
        assertThat(limiter.acquire("one")).isPositive();
        assertThat(limiter.acquire("two")).isZero();
    }

    @Test
    void aSlowRateNeedsALongerWait() {
        RateLimiter limiter = limiter(6, 1);
        limiter.acquire("k");

        assertThat(TimeUnit.NANOSECONDS.toSeconds(limiter.acquire("k"))).isBetween(9L, 10L);
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new RateLimitProperties(true, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(true, 60, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
