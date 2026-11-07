package com.thanhdatpb.listingguard.personalization;

/**
 * Whether every part of the screening ran: every character rule, and the trademark scan (ADR-016, ADR-010).
 * {@code INCOMPLETE} keeps the findings that were established and names what did not run; it is never reported as a clean
 * result, never cached and never charged. Keep in sync with {@code ck_personalization_screening_completeness}.
 */
public enum Completeness {
    COMPLETE,
    INCOMPLETE
}
