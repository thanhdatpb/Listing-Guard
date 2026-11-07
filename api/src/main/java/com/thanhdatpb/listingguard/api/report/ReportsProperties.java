package com.thanhdatpb.listingguard.api.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits and defaults of the listing health report (ADR-018). A value that makes no sense stops the start-up.
 *
 * @param maxDays     the longest period a report may cover, in days counting both ends
 * @param topRules    how many rules a ranked list of rules holds (1 to {@value #MAX_TOP})
 * @param topMarks    how many marks the ranked list of trademark marks holds (1 to {@value #MAX_TOP})
 * @param defaultDays the length of the period a caller gets when it names none (the job and the console page): this many
 *                    complete UTC days before today
 */
@ConfigurationProperties(prefix = "listing-guard.reports")
public record ReportsProperties(
        @DefaultValue("92") int maxDays,
        @DefaultValue("10") int topRules,
        @DefaultValue("10") int topMarks,
        @DefaultValue("7") int defaultDays) {

    public static final int MAX_TOP = 50;

    public ReportsProperties {
        if (maxDays < 1) {
            throw new IllegalArgumentException("listing-guard.reports.max-days must be at least 1");
        }
        if (topRules < 1 || topRules > MAX_TOP) {
            throw new IllegalArgumentException("listing-guard.reports.top-rules must be between 1 and " + MAX_TOP);
        }
        if (topMarks < 1 || topMarks > MAX_TOP) {
            throw new IllegalArgumentException("listing-guard.reports.top-marks must be between 1 and " + MAX_TOP);
        }
        if (defaultDays < 1 || defaultDays > maxDays) {
            throw new IllegalArgumentException("listing-guard.reports.default-days must be between 1 and max-days (" + maxDays + ")");
        }
    }
}
