package com.thanhdatpb.listingguard.api.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=health-report --client=<name|id|all> [--from=... --to=...] [--marketplace=...]}
 * writes {@code report.json} and {@code report.html} (see {@link HealthReportJob} and ADR-018) and the application exits (the
 * profile turns the web server off). It reads the database and writes local files only; it makes no network call, calls no
 * model and writes nothing to the database. A refused option or period makes the process exit with a non-zero code.
 *
 * <p>A runner and not an HTTP endpoint on purpose, like the admin, ingest, trademark-import and batch-check jobs: it is operator
 * work that names any client, and an endpoint that could would be attack surface (ADR-007). The client of the HTTP endpoint is
 * always the owner of the API key.
 */
@Component
@Profile("health-report")
public class HealthReportRunner implements ApplicationRunner {

    private final HealthReportJobProperties properties;
    private final ClientRepository clients;
    private final HealthReportService service;
    private final ObjectMapper mapper;
    private final TrademarkRulesets trademarkRulesets;
    private final Clock clock;

    public HealthReportRunner(HealthReportJobProperties properties, ClientRepository clients, HealthReportService service,
                              ObjectMapper mapper, TrademarkRulesets trademarkRulesets, Clock clock) {
        this.properties = properties;
        this.clients = clients;
        this.service = service;
        this.mapper = mapper;
        this.trademarkRulesets = trademarkRulesets;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        HealthReportOptions options = HealthReportOptions.parse(args);
        new HealthReportJob(clients, service, mapper, trademarkRulesets.ruleset().disclaimer(), properties.outDir(), System.out, clock)
                .run(options);
    }
}
