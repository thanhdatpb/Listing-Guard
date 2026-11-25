package com.thanhdatpb.listingguard.api.evaluation.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.api.listing.AmazonNormalizer;
import com.thanhdatpb.listingguard.api.listing.EtsyNormalizer;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * The CI gate of ADR-032: the labelled sample set of {@code eval/listing/cases/} scored with the rulesets the application
 * serves (read from {@code application.yml}, so the two cannot differ) and the DEMO catalog, against
 * {@code eval/listing/baseline.json}. It fails when the precision of a rule whose source is {@code verified} falls below the
 * baseline, or when a case cannot be analysed completely. No Spring, no database, no Docker.
 *
 * <p>The report goes to {@code api/target/eval-listing/report.md}; CI adds it to the job summary. After an intended change,
 * write the baseline from a real run and commit it with the change:
 * {@code ./mvnw -B -pl api -am test -Dtest=ListingEvalGateTest -Dsurefire.failIfNoSpecifiedTests=false -Deval.listing.update=true}.
 */
public class ListingEvalGateTest {

    public static final Path LISTING_DIR = Path.of("..", "eval", "listing");
    public static final Path DEMO_DIR = Path.of("..", "catalogs", "demo");
    static final Path REPORT_DIR = Path.of("target", "eval-listing");

    @Test
    void theVerifiedRulesKeepTheirPrecisionOnTheLabelledSampleSet() throws IOException {
        boolean update = Boolean.getBoolean("eval.listing.update");

        ListingEvalRunner.Outcome outcome = ListingEvalRunner.evaluate(LISTING_DIR, DEMO_DIR, normalizers(),
                new RuleEngine(RuleRegistry.withDefaults()), activeRulesets(), trademarkRuleset(), REPORT_DIR, update);

        assertThat(outcome.result().cases()).as("the sample set has at least 20 cases").isGreaterThanOrEqualTo(20);
        assertThat(outcome.result().problems()).as("cases whose analysis was not complete").isEmpty();
        assertThat(outcome.regressions()).as("verified rules whose precision fell below eval/listing/baseline.json; see "
                + REPORT_DIR.resolve(ListingEvalReport.MARKDOWN_FILE).toAbsolutePath()).isEmpty();
    }

    static Map<String, Normalizer> normalizerMap() {
        Map<String, Normalizer> map = new HashMap<>();
        for (Normalizer normalizer : List.of(new EtsyNormalizer(), new AmazonNormalizer())) {
            map.put(normalizer.marketplace(), normalizer);
        }
        return map;
    }

    public static java.util.function.Function<String, Normalizer> normalizers() {
        Map<String, Normalizer> map = normalizerMap();
        return marketplace -> map.get(marketplace.trim().toUpperCase(Locale.ROOT));
    }

    /** The listing-rules rulesets {@code listing-guard.rulesets.locations} names, by marketplace. */
    public static java.util.function.Function<String, RulesetConfig> activeRulesets() throws IOException {
        Map<String, RulesetConfig> byMarketplace = new HashMap<>();
        for (JsonNode location : application().path("listing-guard").path("rulesets").path("locations")) {
            RulesetConfig config = RulesetLoader.loadClasspath(classpath(location.asText()));
            byMarketplace.put(config.marketplace().toUpperCase(Locale.ROOT), config);
        }
        assertThat(byMarketplace).containsKeys("ETSY", "AMAZON");
        return marketplace -> byMarketplace.get(marketplace.toUpperCase(Locale.ROOT));
    }

    /** The trademark ruleset {@code listing-guard.trademark.ruleset} names. */
    public static TrademarkRuleset trademarkRuleset() throws IOException {
        String location = application().path("listing-guard").path("trademark").path("ruleset").asText();
        var defaults = new PropertySourcesPropertyResolver(new MutablePropertySources());
        return TrademarkRulesetLoader.loadClasspath(classpath(defaults.resolveRequiredPlaceholders(location)));
    }

    private static JsonNode application() throws IOException {
        return new ObjectMapper(new YAMLFactory()).readTree(Files.readString(
                Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8));
    }

    private static String classpath(String location) {
        assertThat(location).startsWith("classpath:");
        return location.substring("classpath:".length());
    }
}
