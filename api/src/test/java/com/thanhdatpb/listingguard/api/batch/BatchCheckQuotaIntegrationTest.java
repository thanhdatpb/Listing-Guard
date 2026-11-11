package com.thanhdatpb.listingguard.api.batch;

import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_HEADER;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.ETSY_MAPPING;
import static com.thanhdatpb.listingguard.api.batch.BatchCheckJobIntegrationTest.csv;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.countOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.quota;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.setUsed;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The batch job with plans on (ADR-013). It stops at once, before any report, when the plan has no {@code batch} pack or
 * when no unit is left; when the quota runs out in the middle of a file it goes on, answers the rows whose results are
 * stored, marks the others {@code QUOTA_EXCEEDED} and reports how many in {@code listings.csv}, {@code summary.html} and on
 * the console; a plan without {@code trademark-risk} runs the rules only.
 */
@SpringBootTest(properties = "listing-guard.plans.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class BatchCheckQuotaIntegrationTest {

    @Autowired
    ClientRepository clients;

    @Autowired
    ClientProvisioning provisioning;

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

    @Autowired
    PlanService plans;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @TempDir
    Path dir;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(PlanTestSupport.NOVEMBER_15);
    }

    private BatchCheckJob job(ByteArrayOutputStream console) {
        return new BatchCheckJob(clients, normalizers, checks, trademarkChecks, runtime, rulesets, plans,
                new PrintStream(console, true, StandardCharsets.UTF_8), Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
    }

    private BatchOptions options(TestClient client, String... rows) throws Exception {
        Path input = Files.writeString(dir.resolve("in.csv"), ETSY_HEADER + "\n" + String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path mapping = Files.writeString(dir.resolve("map.yaml"), ETSY_MAPPING, StandardCharsets.UTF_8);
        return new BatchOptions(input, mapping, client.name(), "ETSY", dir.resolve("out"), null, null);
    }

    private static String row(String sku, String title) {
        return sku + "," + title + ",d,mug,5,9.99,i_did,made_to_order,";
    }

    private static String text(ByteArrayOutputStream console) {
        return console.toString(StandardCharsets.UTF_8);
    }

    // ---- stops at once

    @Test
    void stopsAtOnceWithQuotaExceededWhenNoUnitIsLeftAndWritesNoReport() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER));
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        BatchOptions options = options(seller, row("A-1", "Plain mug " + unique()), row("A-2", "Plain mug " + unique()));

        assertThatThrownBy(() -> job(console).run(options))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.plan()).isEqualTo("SELLER");
                    assertThat(e.used()).isEqualTo(e.quota());
                });

        assertThat(text(console)).contains("quota-exceeded");
        assertThat(dir.resolve("out")).as("no report directory, so nothing looks like a finished run").doesNotExist();
        assertThat(countOf(jdbc, "listing_check", seller.id())).isZero();
        assertThat(PlanTestSupport.used(jdbc, seller.id(), NOVEMBER)).isEqualTo(quota(jdbc, PlanCode.SELLER));
    }

    @Test
    void aMonthWithOneUnitLeftIsNotStoppedAtOnce() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota(jdbc, PlanCode.SELLER) - 1);
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        BatchCheckJob.Result result = job(console).run(options(seller, row("B-1", "Plain mug " + unique())));

        assertThat(result.rows()).isEqualTo(1);
        assertThat(result.quotaSkippedRows()).as("the rules took the last unit, the trademark part was left out").isEqualTo(1);
    }

    @Test
    void stopsWithPlanNotAllowedForAPlanWithoutTheBatchPack() throws Exception {
        TestClient free = newClient(provisioning, clients, PlanCode.FREE);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        BatchOptions options = options(free, row("F-1", "Plain mug " + unique()));

        assertThatThrownBy(() -> job(console).run(options))
                .isInstanceOfSatisfying(PlanNotAllowedException.class, e -> {
                    assertThat(e.plan()).isEqualTo("FREE");
                    assertThat(e.pack()).isEqualTo(Pack.BATCH);
                });

        assertThat(text(console)).contains("plan-not-allowed");
        assertThat(dir.resolve("out")).doesNotExist();
        assertThat(countOf(jdbc, "listing_check", free.id())).isZero();
    }

    // ---- the quota runs out in the middle

    @Test
    void whenTheQuotaRunsOutInTheMiddleItAnswersStoredRowsMarksTheOthersAndReportsTheNumber() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        int quota = quota(jdbc, PlanCode.SELLER);
        setUsed(jdbc, seller.id(), NOVEMBER, quota - 3);
        String first = "Plain mug " + unique();
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        // 3 units left. Row 1 takes two (rules and trademark), row 2 takes the last one for its rules and has no unit for its
        // trademark, row 3 has none for either, and row 4 repeats row 1, so both its results are stored and cost nothing.
        BatchCheckJob.Result result = job(console).run(options(seller,
                row("Q-1", first),
                row("Q-2", "Plain mug " + unique()),
                row("Q-3", "Plain mug " + unique()),
                row("Q-4", first)));

        assertThat(result.rows()).isEqualTo(4);
        assertThat(result.quotaSkippedRows()).isEqualTo(2);
        assertThat(result.failedRows()).as("running out of quota is not a failed check").isZero();
        assertThat(PlanTestSupport.used(jdbc, seller.id(), NOVEMBER)).isEqualTo(quota);

        List<Map<String, String>> listings = csv(dir.resolve("out").resolve("listings.csv"));
        assertThat(listings).extracting(row -> row.get("status")).containsExactly("OK", "QUOTA_EXCEEDED", "QUOTA_EXCEEDED", "OK");
        Map<String, String> second = listings.get(1);
        assertThat(second.get("rulesCheckId")).as("its rules were checked with the last unit").isNotEmpty();
        assertThat(second.get("trademarkRisk")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(second.get("trademarkCheckId")).isEmpty();
        assertThat(second.get("error")).isEqualTo("trademark: quota-exceeded");
        Map<String, String> third = listings.get(2);
        assertThat(third.get("rulesCheckId")).isEmpty();
        assertThat(third.get("trademarkRisk")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(third.get("error")).isEqualTo("rules: quota-exceeded; trademark: quota-exceeded");
        assertThat(listings.get(3).get("rulesCheckId")).as("the repeated listing is answered from the stored result")
                .isEqualTo(listings.get(0).get("rulesCheckId"));
        assertThat(listings.get(3).get("trademarkCheckId")).isEqualTo(listings.get(0).get("trademarkCheckId")).isNotEmpty();

        String summary = Files.readString(dir.resolve("out").resolve("summary.html"), StandardCharsets.UTF_8);
        assertThat(summary).containsPattern("Dòng bị bỏ qua vì hết quota tháng[^<]*</td><td class=\"n\">2</td>");
        assertThat(summary).contains("Hết quota tháng").contains("QUOTA_EXCEEDED");
        assertThat(text(console)).contains("2 row(s) were left out because the monthly quota is used up")
                .contains("quota-exceeded").contains("status QUOTA_EXCEEDED");
    }

    @Test
    void aBatchThatFitsTheQuotaReportsZeroRowsLeftOut() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        BatchCheckJob.Result result = job(console).run(options(seller, row("Z-1", "Plain mug " + unique())));

        assertThat(result.quotaSkippedRows()).isZero();
        assertThat(PlanTestSupport.used(jdbc, seller.id(), NOVEMBER)).isEqualTo(2);
        assertThat(text(console)).doesNotContain("left out");
        assertThat(Files.readString(dir.resolve("out").resolve("summary.html"), StandardCharsets.UTF_8)).doesNotContain("Hết quota tháng");
    }

    // ---- a plan without the trademark pack

    @Test
    void aPlanWithoutTheTrademarkPackRunsTheRulesAndSaysWhyNotTheTrademarkPart() throws Exception {
        TestClient seller = newClient(provisioning, clients, PlanCode.SELLER);
        jdbc.update("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = 'trademark-risk'");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try {
            BatchCheckJob.Result result = job(console).run(options(seller, row("P-1", "Plain mug " + unique())));

            assertThat(result.rows()).isEqualTo(1);
            assertThat(result.failedRows()).isZero();
        } finally {
            jdbc.update("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', 'trademark-risk')");
        }

        Map<String, String> listing = csv(dir.resolve("out").resolve("listings.csv")).get(0);
        assertThat(listing.get("status")).isEqualTo("OK");
        assertThat(listing.get("trademarkRisk")).isEqualTo("NOT_CONFIGURED");
        assertThat(listing.get("rulesCheckId")).isNotEmpty();
        assertThat(text(console)).contains("Trademark checks were not run").contains("plan-not-allowed");
        assertThat(countOf(jdbc, "trademark_check", seller.id())).isZero();
        assertThat(PlanTestSupport.used(jdbc, seller.id(), NOVEMBER)).as("only the rules were counted").isEqualTo(1);
    }
}
