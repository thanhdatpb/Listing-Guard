package com.thanhdatpb.listingguard.api.policymonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ruleset.RulesetProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** One-shot local policy diff job. It never opens an HTTP server or fetches a source URL. */
@Component
@Profile("policy-diff")
public class PolicyMonitorRunner implements ApplicationRunner {
    private final PolicyMonitorSnapshotStore snapshots;
    private final PolicyMonitorProperties properties;
    private final RulesetProperties rulesets;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PolicyMonitorRunner(PolicyMonitorSnapshotStore snapshots, PolicyMonitorProperties properties, RulesetProperties rulesets,
                               ResourceLoader resourceLoader, ObjectMapper mapper, Clock clock) {
        this.snapshots = snapshots;
        this.properties = properties;
        this.rulesets = rulesets;
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("dry-run");
        Path output = args.containsOption("out") && !args.getOptionValues("out").isEmpty()
                ? Path.of(args.getOptionValues("out").getFirst()) : null;
        Path report = new PolicyMonitorJob(new PolicyDiffEngine(snapshots, clock), properties, rulesets,
                resourceLoader, mapper, clock).run(dryRun, output);
        System.out.println("Policy diff report written to " + report.toAbsolutePath() + (dryRun ? " (dry run; snapshot unchanged)." : "."));
    }
}
