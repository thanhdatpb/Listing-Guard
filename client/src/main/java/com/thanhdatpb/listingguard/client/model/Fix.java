package com.thanhdatpb.listingguard.client.model;

/**
 * A verified fix suggestion (schema {@code FixView}, ADR-014). Advice only: nothing applies it.
 *
 * @param action        what it would do
 * @param description   the suggestion in words
 * @param proposedValue the shortened value for {@code REPLACE}, or {@code null}
 */
public record Fix(FixAction action, String description, String proposedValue) {
}
