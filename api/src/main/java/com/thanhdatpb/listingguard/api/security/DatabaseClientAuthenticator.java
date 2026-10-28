package com.thanhdatpb.listingguard.api.security;

import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks a key up by its SHA-256 in the database. Answers (including "unknown key") are kept in memory for the
 * cache time, so a request usually costs no query and a flood of wrong keys cannot hammer the database; the price
 * is that revoking a key or deactivating a client takes up to that long to take effect (ADR-007). The cache is
 * emptied when it grows past {@value #MAX_CACHED} entries.
 *
 * <p>{@code api_key.last_used_at} is written at most once per key per interval, not on every request.
 */
public final class DatabaseClientAuthenticator implements ClientAuthenticator {

    static final int MAX_CACHED = 10_000;

    private static final Logger log = LoggerFactory.getLogger(DatabaseClientAuthenticator.class);

    private record Cached(AuthenticatedClient client, long expiresAt) {
    }

    private final ApiKeyRepository keys;
    private final long cacheNanos;
    private final long touchNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTouch = new ConcurrentHashMap<>();

    public DatabaseClientAuthenticator(ApiKeyRepository keys, Duration cacheTtl, Duration lastUsedInterval) {
        this(keys, cacheTtl, lastUsedInterval, System::nanoTime);
    }

    /** With an explicit clock (nanoseconds), for tests that must not wait in real time. */
    public DatabaseClientAuthenticator(ApiKeyRepository keys, Duration cacheTtl, Duration lastUsedInterval, LongSupplier clock) {
        this.keys = keys;
        this.cacheNanos = cacheTtl.toNanos();
        this.touchNanos = lastUsedInterval.toNanos();
        this.clock = clock;
    }

    @Override
    public Optional<AuthenticatedClient> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isEmpty()) {
            return Optional.empty();
        }
        String hash = ApiKeyHasher.sha256Hex(presentedKey);
        long now = clock.getAsLong();
        Cached cached = cache.get(hash);
        AuthenticatedClient client;
        if (cached != null && now - cached.expiresAt() < 0) {
            client = cached.client();
        } else {
            client = load(hash);
            if (cacheNanos > 0) {
                if (cache.size() >= MAX_CACHED) {
                    cache.clear();
                }
                cache.put(hash, new Cached(client, now + cacheNanos));
            }
        }
        if (client != null) {
            touch(client.keyId(), now);
        }
        return Optional.ofNullable(client);
    }

    private AuthenticatedClient load(String hash) {
        return keys.findByKeyHashWithClient(hash)
                .filter(key -> key.isActive() && key.getClient().isActive())
                .map(key -> new AuthenticatedClient(key.getClient().getId(), key.getClient().getName(), key.getKeyId()))
                .orElse(null);
    }

    private void touch(String keyId, long now) {
        Long previous = lastTouch.get(keyId);
        if (previous != null && now - previous < touchNanos) {
            return;
        }
        lastTouch.put(keyId, now);
        try {
            keys.touch(keyId, Instant.now());
        } catch (RuntimeException e) {
            // a missing timestamp must never fail the request
            log.warn("Could not record last use of key {}: {}", keyId, e.toString());
        }
    }
}
