package com.thanhdatpb.listingguard.api.policy;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=ingest} ingests the policy corpus, then the application
 * exits (the profile turns the web server off). A failure makes the process exit with a non-zero code.
 *
 * <p>This is a runner and not an HTTP endpoint on purpose: there is no authentication yet, and ingest is
 * batch work on local files, so exposing it over HTTP would add attack surface for no benefit.
 */
@Component
@Profile("ingest")
public class PolicyIngestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestRunner.class);

    private final PolicyIngestService service;
    private final PolicyProperties properties;

    public PolicyIngestRunner(PolicyIngestService service, PolicyProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        Path dir = Path.of(properties.dir());
        log.info("Ingesting policies from {}", dir.toAbsolutePath());
        PolicyIngestService.Report report = service.ingest(dir);
        report.documents().forEach(result -> log.info("{} {}/{} ({} chunks)", result.outcome(), result.marketplace(),
                result.file(), result.chunks()));
    }
}
