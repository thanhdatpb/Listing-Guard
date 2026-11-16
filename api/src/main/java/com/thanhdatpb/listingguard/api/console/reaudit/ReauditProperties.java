package com.thanhdatpb.listingguard.api.console.reaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the console's periodic re-audit (ADR-035). The size of a file is the bulk check's ({@code listing-guard.console.bulk.*}).
 *
 * @param keepRuns how many runs of one scope (client, marketplace, account label) are kept; older ones are deleted when a run is
 *                 stored. At least 2: the comparison needs the run before.
 */
@ConfigurationProperties(prefix = "listing-guard.console.reaudit")
public record ReauditProperties(@DefaultValue("20") int keepRuns) {

    public ReauditProperties {
        if (keepRuns < 2) {
            throw new IllegalArgumentException("listing-guard.console.reaudit.keep-runs must be at least 2.");
        }
    }
}
