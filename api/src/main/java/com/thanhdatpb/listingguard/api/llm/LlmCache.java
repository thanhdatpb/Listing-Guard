package com.thanhdatpb.listingguard.api.llm;

import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, expiring, in-memory cache of validated answers, keyed by the hash of the model id and the whole prompt (ADR-036 item 5).
 * It holds answers, never prompts: the key is a hash. A hit costs no request and no quota.
 */
public final class LlmCache {

    private record Entry(String answer, Instant expires) {
    }

    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries;

    public LlmCache(int maxEntries, Duration ttl, Clock clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttl = ttl;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > LlmCache.this.maxEntries;
            }
        };
    }

    /** SHA-256 of the model id and every part of the request that can change the answer. */
    public static String key(String modelId, LlmRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {modelId, request.system(), request.user(), request.jsonSchema(),
                    String.valueOf(request.maxOutputTokens())}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public synchronized Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expires().isAfter(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.answer());
    }

    public synchronized void put(String key, String answer) {
        entries.put(key, new Entry(answer, clock.instant().plus(ttl)));
    }

    public synchronized int size() {
        return entries.size();
    }
}
