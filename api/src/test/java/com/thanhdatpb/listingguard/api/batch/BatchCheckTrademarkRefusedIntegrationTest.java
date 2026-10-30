package com.thanhdatpb.listingguard.api.batch;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.CLIENT_1;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_HEADER;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_MAPPING;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.csv;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.unique;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A configured catalog that cannot be served (REFUSED) is treated like no catalog: the rules run, no trademark result is
 * stored, and the report says the trademark check was not configured, with the reason.
 */
@SpringBootTest(properties = "listing-guard.trademark.catalog-version=configured-but-never-imported-0123456789ab")
@Import(TestcontainersConfig.class)
class BatchCheckTrademarkRefusedIntegrationTest {

    @Autowired
    ClientRepository clients;

    @Autowired
    NormalizerRegistry normalizers;

    @Autowired
    CheckService checks;

    @Autowired
    TrademarkCheckService trademarkChecks;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    TrademarkRulesets rulesets;

    @TempDir
    Path dir;

    @Test
    void theRulesRunAndTheReportNamesTheReasonTheCatalogIsNotServed() throws Exception {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.REFUSED);
        Path input = Files.writeString(dir.resolve("in.csv"), ETSY_HEADER + "\n"
                + "R-1," + unique() + ",d,mug,5,9.99,i_did,made_to_order,\n", StandardCharsets.UTF_8);
        Path mapping = Files.writeString(dir.resolve("map.yaml"), ETSY_MAPPING, StandardCharsets.UTF_8);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        BatchCheckJob job = new BatchCheckJob(clients, normalizers, checks, trademarkChecks, runtime, rulesets,
                new PrintStream(console, true, StandardCharsets.UTF_8), Clock.fixed(Instant.parse("2026-10-30T09:15:30Z"), ZoneOffset.UTC));

        BatchCheckJob.Result result = job.run(new BatchOptions(input, mapping, CLIENT_1, "ETSY", dir.resolve("out"), null, null));

        assertThat(result.rows()).isEqualTo(1);
        assertThat(result.failedRows()).isZero();
        List<Map<String, String>> listings = csv(dir.resolve("out").resolve("listings.csv"));
        assertThat(listings.get(0)).containsEntry("status", "OK").containsEntry("trademarkRisk", "NOT_CONFIGURED")
                .containsEntry("trademarkCheckId", "");
        assertThat(listings.get(0).get("rulesCheckId")).isNotBlank();
        assertThat(Files.readString(dir.resolve("out").resolve("summary.html"), StandardCharsets.UTF_8))
                .contains("<b>Trademark chưa cấu hình</b>").contains("REFUSED")
                .contains("configured-but-never-imported-0123456789ab");
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Trademark checks were not run (REFUSED");
    }
}
