package com.thanhdatpb.listingguard.api.check;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param planned marketplaces that are on the roadmap but not supported yet; a request for one gets 422
 *                "not yet supported" instead of the 400 "unknown marketplace"
 */
@ConfigurationProperties(prefix = "listing-guard.marketplaces")
public record MarketplaceProperties(@DefaultValue List<String> planned) {
}
