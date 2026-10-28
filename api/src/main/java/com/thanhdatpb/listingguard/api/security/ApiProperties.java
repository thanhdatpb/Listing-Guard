package com.thanhdatpb.listingguard.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxBodyBytes     requests with a larger body get 413 (256 KB by default)
 * @param swaggerUiEnabled development only: serves Swagger UI and lets it and the OpenAPI document be read without
 *                         an API key. Off by default; with it off, /v3/api-docs needs a key like everything else.
 */
@ConfigurationProperties(prefix = "listing-guard.api")
public record ApiProperties(@DefaultValue("262144") long maxBodyBytes, @DefaultValue("false") boolean swaggerUiEnabled) {
}
