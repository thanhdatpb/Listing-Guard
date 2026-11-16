package com.thanhdatpb.listingguard.api.llm;

/**
 * The model step cannot run for this call (ADR-036 item 4). It is not an error of the check: the caller skips the step and records
 * it in the result's completeness. The message never carries a key, a prompt or listing text.
 */
public class LlmUnavailableException extends RuntimeException {

    public enum Reason {
        /** {@code listing-guard.llm.enabled} is false. */
        DISABLED,
        /** No configured provider is allowed to receive the kinds of data of this call. */
        NO_ELIGIBLE_PROVIDER,
        /** Every eligible provider is at its per-minute, per-day or cool-down limit; nothing was sent. */
        QUOTA_EXHAUSTED,
        /** At least one provider was asked and failed (timeout, 429, 5xx, unreadable or truncated answer) and none answered. */
        PROVIDERS_FAILED
    }

    private final Reason reason;

    public LlmUnavailableException(Reason reason) {
        super("The LLM step cannot run: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
