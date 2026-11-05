package com.thanhdatpb.listingguard.api.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client plans and the monthly quota (ADR-013). Off by default: while {@code enabled} is false nothing about a plan is
 * read, written or enforced, so a deployment that hosts the code for itself behaves as it did before plans existed.
 */
@ConfigurationProperties(prefix = "listing-guard.plans")
public record PlanProperties(@DefaultValue("false") boolean enabled) {
}
