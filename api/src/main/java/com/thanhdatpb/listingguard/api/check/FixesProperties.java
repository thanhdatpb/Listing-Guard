package com.thanhdatpb.listingguard.api.check;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "listing-guard.fixes")
public record FixesProperties(@DefaultValue("false") boolean enabled) {
}
