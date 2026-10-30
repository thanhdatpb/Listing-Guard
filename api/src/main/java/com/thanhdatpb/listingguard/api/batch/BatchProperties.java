package com.thanhdatpb.listingguard.api.batch;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the batch job.
 *
 * @param dir the directory of the batch files (mappings, inputs, reports); the reports go to {@code <dir>/out} unless
 *            {@code --out} says otherwise. The compose service mounts the {@code batch/} directory of the repository
 *            and sets it to {@code /batch}.
 */
@ConfigurationProperties(prefix = "listing-guard.batch")
public record BatchProperties(@DefaultValue("batch") String dir) {

    Path defaultOut() {
        return Path.of(dir).resolve("out");
    }
}
