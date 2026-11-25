package com.thanhdatpb.listingguard.api.rewrite;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * At most {@code max-per-hour} runs per client in any rolling hour (ADR-037), counted in memory: each run is a paid model call. A
 * deployment with several instances gets the limit per instance. A press is one run however many rounds it takes (ADR-050); the
 * provider's own per-minute and per-day limits (ADR-036) count each round's request.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.llm.rewrite", name = "enabled", havingValue = "true")
public class RewriteRateLimit {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final RewriteProperties properties;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> runs = new ConcurrentHashMap<>();

    public RewriteRateLimit(RewriteProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Counts one run and returns true, or returns false (counting nothing) when the client already used its runs of this hour. */
    public boolean tryAcquire(UUID clientId) {
        Instant now = clock.instant();
        boolean[] allowed = new boolean[1];
        runs.compute(clientId, (id, times) -> {
            Deque<Instant> window = times == null ? new ArrayDeque<>() : times;
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.removeFirst();
            }
            allowed[0] = window.size() < properties.maxPerHour();
            if (allowed[0]) {
                window.addLast(now);
            }
            return window;
        });
        return allowed[0];
    }
}
