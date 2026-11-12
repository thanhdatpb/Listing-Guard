package com.thanhdatpb.listingguard.client.model;

/**
 * Publish readiness of the listing-rules pack (schema {@code ReadinessView}, ADR-014). It is no trademark verdict: show the
 * trademark result next to it.
 *
 * @param ready   whether the listing has no blocking finding
 * @param score   the score, or {@code null} when the run was incomplete
 * @param status  the readiness status as the API names it
 * @param version the readiness formula's version
 */
public record Readiness(boolean ready, Integer score, String status, String version) {
}
