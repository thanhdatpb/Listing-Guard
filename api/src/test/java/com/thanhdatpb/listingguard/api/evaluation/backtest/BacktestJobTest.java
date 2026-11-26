package com.thanhdatpb.listingguard.api.evaluation.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.batch.BatchConfigException;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.evaluation.listing.DemoCatalogScanner;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvalGateTest;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The backtest of ADR-032 on a synthetic file: the active Etsy ruleset and the DEMO catalog (invented marks), no Spring, no
 * database. Every number of the report is checked against what the rows were built to give, and the report is checked to hold
 * no listing text.
 */
class BacktestJobTest {

    private static final String HEADER = "sku,title,tags,description,price,quantity,who_made,when_made,category,claimed,issue,mark";
    private static final String PLAIN_REST = "ceramic mug|stoneware,A stoneware mug finished with a blue glaze.,28.00,4,i_did,"
            + "made_to_order,Home & Living > Mugs";
    private static final String SECRET = "SECRET-DESCRIPTION-TEXT-7731";
    private static final String LONG_TITLE = "Handmade Stoneware Coffee Mug with Hand Painted Cobalt Blue Glaze Dishwasher Safe "
            + "Kitchenware Large Housewarming Present for Tea Lovers and Bakers and Friends";
    private static final String MAPPING = """
            id: sku
            title: title
            tags: {column: tags, separator: "|"}
            description: description
            price: price
            quantity: quantity
            who_made: who_made
            when_made: when_made
            category: category
            """;

    private static Function<String, RulesetConfig> rulesets;
    private static TrademarkScanner scanner;

    @TempDir
    Path dir;

    @BeforeAll
    static void load() throws IOException {
        rulesets = ListingEvalGateTest.activeRulesets();
        scanner = DemoCatalogScanner.load(ListingEvalGateTest.trademarkRuleset(), ListingEvalGateTest.DEMO_DIR);
    }

    private static String plain(String sku, String title, String claimed, String issue, String mark) {
        return String.join(",", sku, title, PLAIN_REST, claimed, issue, mark);
    }

    private List<String> rows() {
        List<String> rows = new ArrayList<>();
        rows.add(HEADER);
        rows.add(plain("S-1", "Zibblefrax Ceramic Coffee Mug", "yes", "trademark", "Zibblefrax"));       // caught on its mark
        rows.add(plain("S-2", "Plimbasta Ceramic Coffee Mug", "yes", "trademark", "Plimbusto"));         // near spelling
        rows.add(plain("S-3", "Ceramic Coffee Mug", "yes", "trademark", "Brightloom"));                  // not in catalog
        rows.add(plain("S-4", "Ceramic Coffee Mug", "Yes", "Trademark", "Snazzlefrop"));                 // not in the text
        rows.add(plain("S-5", "Ceramic Coffee Mug", "yes", "copyright", ""));                            // no part covers it
        rows.add(plain("S-6", "\"" + LONG_TITLE + "\"", "yes", "listing-policy", ""));                   // caught by a rule
        rows.add("S-7,Ceramic Coffee Mug,ceramic mug|stoneware," + SECRET + ",28.00,4,i_did,made_to_order,Home & Living > Mugs,"
                + "no,,");                                                                                // clear
        rows.add(plain("S-8", "Ceramic Coffee Mug Free Shipping", "0", "", ""));                         // flagged, unclaimed
        rows.add("S-9,Ceramic Coffee Mug,ceramic mug,yes");                                              // missing cells
        rows.add(plain("S-10", "Ceramic Coffee Mug", "maybe", "", ""));                                  // strange label
        rows.add(plain("S-11", "Ceramic Coffee Mug", "yes", "trade mark?", ""));                         // strange issue
        rows.add(plain("S-12", "Ceramic Coffee Mug", "", "", ""));                                       // no label
        rows.add(plain("S-13", "Ceramic Coffee Mug", "yes", "trademark", ""));                           // no mark named
        return rows;
    }

    private BacktestOptions options(Path input, Path mapping) {
        return new BacktestOptions(input, mapping, "etsy", "claimed", "issue", "mark", dir.resolve("out"), null,
                Severity.WARNING, RiskLevel.MEDIUM, BacktestOptions.DEFAULT_VARIANT_THRESHOLD);
    }

    private BacktestJob job(ListingAnalyzer.ScannerLookup lookup, String state, ByteArrayOutputStream console) {
        ListingAnalyzer analyzer = new ListingAnalyzer(ListingEvalGateTest.normalizers(),
                new RuleEngine(RuleRegistry.withDefaults()), rulesets, lookup);
        return new BacktestJob(analyzer, rulesets, marketplace -> new BacktestJob.TrademarkInfo(state,
                state.equals("READY") ? scanner.catalog().version() : null,
                state.equals("READY") ? scanner.ruleset().version() : null, null),
                new PrintStream(console, true, StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-11-16T08:00:00Z"), ZoneOffset.UTC));
    }

    private Path write(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    @Test
    void reportsBoundsForUnclaimedProbeRowsAndKeepsClaimMetricsSeparate() throws IOException {
        Path input = write("probe.csv", HEADER + ",expected_min_risk,expected_max_risk\n"
                + plain("S-probe", "Zibblefrax Ceramic Coffee Mug", "", "", "Zibblefrax") + ",MEDIUM,\n"
                + plain("S-max", "Zibblefrax Ceramic Coffee Mug", "", "", "") + ",,MEDIUM\n");
        Path mapping = write("mapping.yaml", MAPPING);
        var catalog = new com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog("probe", List.of(
                new com.thanhdatpb.listingguard.trademark.catalog.CatalogMark("zibblefrax", "Zibblefrax", List.of(25),
                        com.thanhdatpb.listingguard.trademark.catalog.MarkStatus.REGISTERED, false, List.of(),
                        new com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals(1, 4L, 1,
                                com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.MarkKind.STANDARD_CHARACTER,
                                false, com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Tier.STRONG,
                                com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals.Reason.UNIQUE_OWNER_WORD, 1L))));
        var probeScanner = new TrademarkScanner(scanner.ruleset(), catalog);
        BacktestJob.Result result = job(marketplace -> probeScanner, "READY", new ByteArrayOutputStream()).run(options(input, mapping));
        assertThat(result.claimed()).isZero();
        JsonNode report = new ObjectMapper().readTree(result.reportDir().resolve(BacktestReport.JSON_FILE).toFile());
        var passes = report.path("probes").get(0);
        assertThat(passes.path("passed").asBoolean()).isTrue();
        assertThat(passes.path("tier").asText()).isEqualTo("STRONG");
        assertThat(passes.path("owners").asLong()).isEqualTo(1L);
        assertThat(passes.path("serials").asLong()).isEqualTo(4L);
        assertThat(passes.path("namedMarkTier").asText()).isEqualTo("STRONG");
        assertThat(passes.path("findingKeys").isEmpty()).isFalse();
        assertThat(report.path("probes").get(1).path("passed").asBoolean()).isFalse();
        assertThat(Files.readString(result.reportDir().resolve(BacktestReport.MARKDOWN_FILE)))
                .contains("ĐẠT", "KHÔNG ĐẠT").doesNotContain("Ceramic Coffee Mug");
    }

    @Test
    void reportsRecallMissReasonsAndFalseAlarmsOfASyntheticFile() throws IOException {
        Path input = write("claims.csv", String.join("\n", rows()) + "\n");
        Path mapping = write("mapping.yaml", MAPPING);
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        BacktestJob.Result result = job(marketplace -> scanner, "READY", console).run(options(input, mapping));

        assertThat(result.rows()).isEqualTo(13);
        assertThat(result.claimed()).isEqualTo(7);
        assertThat(result.caught()).isEqualTo(2);
        assertThat(result.missed()).isEqualTo(5);

        JsonNode report = new ObjectMapper().readTree(dir.resolve("out").resolve(BacktestReport.JSON_FILE).toFile());
        assertThat(report.path("format").asText()).isEqualTo(BacktestReport.FORMAT);
        assertThat(report.path("rows").path("total").asInt()).isEqualTo(13);
        assertThat(report.path("rows").path("checked").asInt()).isEqualTo(10);
        assertThat(report.path("rows").path("unreadable").asInt()).isEqualTo(1);
        assertThat(report.path("rows").path("labelInvalid").asInt()).isEqualTo(2);
        assertThat(report.path("rows").path("unlabeled").asInt()).isEqualTo(1);

        JsonNode claimed = report.path("claimed");
        assertThat(claimed.path("recall").path("numerator").asInt()).isEqualTo(2);
        assertThat(claimed.path("recall").path("denominator").asInt()).isEqualTo(7);
        assertThat(claimed.path("caughtByRules").asInt()).isEqualTo(1);
        assertThat(claimed.path("caughtByTrademark").asInt()).isEqualTo(1);
        assertThat(claimed.path("trademarkClaimsCaughtOnTheirMark").path("numerator").asInt()).isEqualTo(1);
        assertThat(claimed.path("trademarkClaimsCaughtOnTheirMark").path("denominator").asInt()).isEqualTo(4);
        assertThat(issue(claimed, "trademark").path("total").asInt()).isEqualTo(5);
        assertThat(issue(claimed, "trademark").path("caught").asInt()).isEqualTo(1);
        assertThat(issue(claimed, "listing-policy").path("caught").asInt()).isEqualTo(1);
        assertThat(issue(claimed, "copyright").path("missed").asInt()).isEqualTo(1);

        assertThat(missReason(report, "S-2")).isEqualTo(MissReason.SPELLING_VARIANT.name());
        assertThat(missed(report, "S-2").path("detail").asText()).contains("plimbasta");
        assertThat(missReason(report, "S-3")).isEqualTo(MissReason.MARK_NOT_IN_CATALOG.name());
        assertThat(missReason(report, "S-4")).isEqualTo(MissReason.MARK_NOT_IN_TEXT.name());
        assertThat(missReason(report, "S-5")).isEqualTo(MissReason.NOT_COVERED.name());
        assertThat(missReason(report, "S-13")).isEqualTo(MissReason.MARK_NOT_LABELED.name());
        assertThat(report.path("missReasons")).hasSize(5);

        JsonNode unclaimed = report.path("unclaimed");
        assertThat(unclaimed.path("total").asInt()).isEqualTo(2);
        assertThat(unclaimed.path("flagged").asInt()).isEqualTo(1);
        assertThat(unclaimed.path("flaggedShare").path("value").asDouble()).isEqualTo(0.5);

        assertThat(rule(report, "title_length").path("claimedListings").asInt()).isEqualTo(1);
        assertThat(rule(report, "title_length").path("sourceStatus").asText()).isEqualTo("VERIFIED");
        assertThat(rule(report, "title_promotional_terms").path("unclaimedListings").asInt()).isEqualTo(1);
        assertThat(rule(report, "trademark/catalog_exact").path("claimedListings").asInt()).isEqualTo(1);

        assertThat(problem(report, 10).path("status").asText()).isEqualTo("UNREADABLE");
        assertThat(problem(report, 10).path("problem").asText()).contains("ô").contains("cột");
        assertThat(problem(report, 11).path("status").asText()).isEqualTo("LABEL_INVALID");
        assertThat(problem(report, 11).path("problem").asText()).contains("maybe");
        assertThat(problem(report, 12).path("problem").asText()).contains("trade mark?");

        String markdown = Files.readString(dir.resolve("out").resolve(BacktestReport.MARKDOWN_FILE), StandardCharsets.UTF_8);
        assertThat(markdown).contains("**Recall: 2/7 (28.6%)**").contains("SPELLING_VARIANT").contains("1/2 (50.0%)");
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("recall 2/7");
    }

    @Test
    void theReportHoldsNoListingText() throws IOException {
        Path input = write("claims.csv", String.join("\n", rows()) + "\n");
        Path mapping = write("mapping.yaml", MAPPING);

        job(marketplace -> scanner, "READY", new ByteArrayOutputStream()).run(options(input, mapping));

        for (String file : List.of(BacktestReport.JSON_FILE, BacktestReport.MARKDOWN_FILE)) {
            String text = Files.readString(dir.resolve("out").resolve(file), StandardCharsets.UTF_8);
            assertThat(text).as(file).doesNotContain(SECRET).doesNotContain("Plimbasta Ceramic Coffee Mug")
                    .doesNotContain("Cobalt Blue Glaze").doesNotContain("stoneware mug finished");
        }
    }

    @Test
    void aCatalogThatIsNotServedLeavesTheRulesAndSaysWhyTrademarkClaimsWereMissed() throws IOException {
        Path input = write("claims.csv", String.join("\n", rows()) + "\n");
        Path mapping = write("mapping.yaml", MAPPING);
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        BacktestJob.Result result = job(marketplace -> {
            throw new TrademarkUnavailableException("Trademark checks are not configured");
        }, "DISABLED", console).run(options(input, mapping));

        assertThat(result.caught()).isEqualTo(1);
        JsonNode report = new ObjectMapper().readTree(dir.resolve("out").resolve(BacktestReport.JSON_FILE).toFile());
        assertThat(report.path("run").path("trademarkState").asText()).isEqualTo("DISABLED");
        assertThat(missReason(report, "S-1")).isEqualTo(MissReason.TRADEMARK_NOT_SERVED.name());
        assertThat(missReason(report, "S-5")).isEqualTo(MissReason.NOT_COVERED.name());
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("trademark part did not run");
    }

    @Test
    void aMissingLabelColumnStopsTheJobBeforeAnyRowIsRead() throws IOException {
        Path input = write("claims.csv", "sku,title\nS-1,Ceramic Coffee Mug\n");
        Path mapping = write("mapping.yaml", "id: sku\ntitle: title\n");

        assertThatThrownBy(() -> job(marketplace -> scanner, "READY", new ByteArrayOutputStream())
                .run(options(input, mapping))).isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("no label column 'claimed'");
        assertThat(dir.resolve("out")).doesNotExist();
    }

    @Test
    void aDamagedFileCompletesTheReportAsUnfinishedAndFails() throws IOException {
        Path input = write("claims.csv", HEADER + "\n" + plain("S-1", "Ceramic Coffee Mug", "no", "", "") + "\n"
                + "S-2,\"unclosed quote,x\n");
        Path mapping = write("mapping.yaml", MAPPING);

        assertThatThrownBy(() -> job(marketplace -> scanner, "READY", new ByteArrayOutputStream())
                .run(options(input, mapping))).isInstanceOf(IllegalStateException.class).hasMessageContaining("stopped");
        String markdown = Files.readString(dir.resolve("out").resolve(BacktestReport.MARKDOWN_FILE), StandardCharsets.UTF_8);
        assertThat(markdown).contains("CHƯA XONG");
    }

    private static JsonNode issue(JsonNode claimed, String code) {
        for (JsonNode issue : claimed.path("byIssue")) {
            if (issue.path("issue").asText().equals(code)) {
                return issue;
            }
        }
        throw new AssertionError("no issue " + code + " in " + claimed);
    }

    private static JsonNode missed(JsonNode report, String id) {
        for (JsonNode missed : report.path("missed")) {
            if (missed.path("id").asText().equals(id)) {
                return missed;
            }
        }
        throw new AssertionError("no missed row " + id + " in " + report.path("missed"));
    }

    private static String missReason(JsonNode report, String id) {
        return missed(report, id).path("reason").asText();
    }

    private static JsonNode rule(JsonNode report, String key) {
        for (JsonNode rule : report.path("rules")) {
            if (rule.path("rule").asText().equals(key)) {
                return rule;
            }
        }
        throw new AssertionError("no rule " + key + " in " + report.path("rules"));
    }

    private static JsonNode problem(JsonNode report, int line) {
        for (JsonNode problem : report.path("problems")) {
            if (problem.path("line").asInt() == line) {
                return problem;
            }
        }
        throw new AssertionError("no problem at line " + line + " in " + report.path("problems"));
    }
}
