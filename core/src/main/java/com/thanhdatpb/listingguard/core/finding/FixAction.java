package com.thanhdatpb.listingguard.core.finding;

/**
 * What a {@link FixSuggestion} proposes to do to the value a finding is about (ADR-014). A suggestion is advice
 * only: nothing in Listing Guard applies it.
 */
public enum FixAction {
    /** Replace the value with {@link FixSuggestion#proposedValue()}. */
    REPLACE,
    /** Drop the value; there is no proposed value. */
    REMOVE
}
