package com.thanhdatpb.listingguard.rag.llm;

/**
 * Asks a language model for one structured answer. The rest of the system depends on this interface only,
 * so the vendor is replaceable (ADR-011 item 4). Implementations send temperature 0 and never log keys or
 * listing text.
 */
public interface LlmProvider {

    /** Identifies provider and model, for example {@code scripted:v1}. Part of the analysis version. */
    String modelId();

    /**
     * One request, one answer, no retry and no tools.
     *
     * @throws LlmException timeout, rate limit, a bad HTTP status or an answer cut off by the token limit
     */
    LlmResponse complete(LlmRequest request);
}
