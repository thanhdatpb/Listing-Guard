package com.thanhdatpb.listingguard.api.report;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the {@code health-report} job (ADR-018).
 *
 * @param dir the directory of the report files: a run writes {@code <dir>/out/<UTC timestamp>/}. The compose services mount
 *            the {@code health-report/} directory of the repository and set it to {@code /health-report}.
 */
@ConfigurationProperties(prefix = "listing-guard.health-report")
public record HealthReportJobProperties(@DefaultValue("health-report") String dir) {

    Path outDir() {
        return Path.of(dir).resolve("out");
    }
}
