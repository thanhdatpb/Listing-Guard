package com.thanhdatpb.listingguard.client.model;

/**
 * Whether checks return fix suggestions (schema {@code FixesCapability}).
 *
 * @param enabled whether {@code POST /api/v1/checks} returns verified fixes and readiness
 */
public record FixesCapability(boolean enabled) {
}
