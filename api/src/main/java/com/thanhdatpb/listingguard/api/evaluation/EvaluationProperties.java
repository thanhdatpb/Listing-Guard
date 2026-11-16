package com.thanhdatpb.listingguard.api.evaluation;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the two evaluation jobs of ADR-032.
 *
 * @param backtestDir     the directory of the {@code backtest} job: a run without {@code --out} writes
 *                        {@code <dir>/out/<UTC timestamp>/}. The compose service mounts {@code eval/backtest/} there.
 * @param listingDir      the labelled sample set of the {@code eval-listing} job: {@code <dir>/cases/*.yaml}, the baseline
 *                        {@code <dir>/baseline.json}; its report goes to {@code <dir>/out/}
 * @param demoCatalogDir  the DEMO trademark catalog the sample set is scanned with ({@code catalogs/demo/}, fictional marks);
 *                        never the catalog the deployment serves, so the expected findings do not depend on it
 */
@ConfigurationProperties(prefix = "listing-guard.evaluation")
public record EvaluationProperties(@DefaultValue("eval/backtest") String backtestDir,
                                   @DefaultValue("eval/listing") String listingDir,
                                   @DefaultValue("catalogs/demo") String demoCatalogDir) {

    public Path backtestOut() {
        return Path.of(backtestDir).resolve("out");
    }
}
