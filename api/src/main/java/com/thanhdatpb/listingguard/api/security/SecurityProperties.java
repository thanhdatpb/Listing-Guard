package com.thanhdatpb.listingguard.api.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param apiKeys {@code name:key} entries from the environment variable {@code LISTING_GUARD_API_KEYS} */
@ConfigurationProperties(prefix = "listing-guard.security")
public record SecurityProperties(@DefaultValue List<String> apiKeys) {
}
