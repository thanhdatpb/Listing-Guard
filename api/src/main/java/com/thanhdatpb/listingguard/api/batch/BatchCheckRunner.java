package com.thanhdatpb.listingguard.api.batch;

import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=batch-check --input=... --mapping=... --client=... --marketplace=...}
 * checks every listing of a CSV file and writes the reports (see {@link BatchCheckJob} and {@code docs/batch-check.md}),
 * then the application exits (the profile turns the web server off). It reads local files and the database and makes no
 * network call. A refused option, mapping or input file makes the process exit with a non-zero code.
 *
 * <p>A runner and not an HTTP endpoint on purpose, like the admin, ingest and trademark-import jobs: it is operator work
 * on local files, and an endpoint would be attack surface for no benefit.
 */
@Component
@Profile("batch-check")
public class BatchCheckRunner implements ApplicationRunner {

    private final BatchProperties properties;
    private final ClientRepository clients;
    private final NormalizerRegistry normalizers;
    private final CheckService checks;
    private final TrademarkCheckService trademarkChecks;
    private final TrademarkCatalogRuntime trademark;
    private final TrademarkRulesets trademarkRulesets;

    public BatchCheckRunner(BatchProperties properties, ClientRepository clients, NormalizerRegistry normalizers,
                            CheckService checks, TrademarkCheckService trademarkChecks, TrademarkCatalogRuntime trademark,
                            TrademarkRulesets trademarkRulesets) {
        this.properties = properties;
        this.clients = clients;
        this.normalizers = normalizers;
        this.checks = checks;
        this.trademarkChecks = trademarkChecks;
        this.trademark = trademark;
        this.trademarkRulesets = trademarkRulesets;
    }

    @Override
    public void run(ApplicationArguments args) {
        BatchOptions options = BatchOptions.parse(args, properties.defaultOut());
        new BatchCheckJob(clients, normalizers, checks, trademarkChecks, trademark, trademarkRulesets, System.out,
                Clock.systemUTC()).run(options);
    }
}
