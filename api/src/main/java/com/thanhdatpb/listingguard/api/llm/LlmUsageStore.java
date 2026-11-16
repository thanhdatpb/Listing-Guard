package com.thanhdatpb.listingguard.api.llm;

import java.time.LocalDate;
import java.util.Map;

/** Counts the requests sent to each provider per UTC day (ADR-036 item 4). It stores numbers only, never a prompt or an answer. */
public interface LlmUsageStore {

    /** Takes one request of {@code day} for the provider, atomically; false when the day already holds {@code limit} requests. */
    boolean reserve(String providerId, LocalDate day, int limit);

    /** Adds the tokens the vendor reported for a request. */
    void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens);

    /** The requests sent on {@code day}, by provider id. */
    Map<String, Long> requestsOn(LocalDate day);
}
