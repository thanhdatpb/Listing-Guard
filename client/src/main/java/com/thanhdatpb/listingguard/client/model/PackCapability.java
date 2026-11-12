package com.thanhdatpb.listingguard.client.model;

import java.util.Map;

/**
 * One analysis pack the deployment serves (schema {@code PackCapability}).
 *
 * @param id              the pack id, for example {@code listing-rules}
 * @param available       whether its endpoint can answer now
 * @param rulesetVersions the active ruleset version per marketplace
 */
public record PackCapability(String id, boolean available, Map<String, String> rulesetVersions) {

    public PackCapability {
        rulesetVersions = Copies.map(rulesetVersions);
    }
}
