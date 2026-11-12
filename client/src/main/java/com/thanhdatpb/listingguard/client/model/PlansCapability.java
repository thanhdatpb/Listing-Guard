package com.thanhdatpb.listingguard.client.model;

/**
 * Whether plans apply (schema {@code PlansCapability}). The caller's own plan is in {@link Usage}.
 *
 * @param enforced whether plans and the monthly quota are applied
 */
public record PlansCapability(boolean enforced) {
}
