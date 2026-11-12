package com.thanhdatpb.listingguard.api.policymonitor;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "listing-guard.policy-monitor")
public record PolicyMonitorProperties(@DefaultValue("policies/manifest.yaml") Path manifest,
                                      @DefaultValue("policies/raw") Path rawDir,
                                      @DefaultValue("policy-monitor") Path reportDir,
                                      @DefaultValue("90") int staleDays,
                                      @DefaultValue("12") int keepReports) {
    public PolicyMonitorProperties {
        if (staleDays < 0) throw new IllegalArgumentException("policy-monitor.stale-days must be zero or more");
        if (keepReports < 1) throw new IllegalArgumentException("policy-monitor.keep-reports must be at least one");
    }
}
