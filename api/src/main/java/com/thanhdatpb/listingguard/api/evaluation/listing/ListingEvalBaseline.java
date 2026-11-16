package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.Result;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.RuleScore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The precision and recall per rule that the labelled sample set gave at its last accepted run, kept in the repository as
 * {@code eval/listing/baseline.json} (ADR-032). It is written only by a real run ({@code --update-baseline} of the job, or
 * {@code -Deval.listing.update=true} of the gate test), never by hand, and a change to it is reviewed in the pull request.
 *
 * <p>The gate: for every rule whose source is {@code verified} in the active ruleset, precision may not fall below the
 * baseline's. A rule the baseline does not have, or whose precision either side cannot compute (no finding at all), is not
 * compared; the report lists it. Recall is reported and not gated (ADR-032 says why).
 */
public record ListingEvalBaseline(String format, Map<String, String> rulesetVersions, String trademarkRulesetVersion,
                                  int cases, List<Entry> rules) {

    public static final String FORMAT = "listing-guard/eval-listing-baseline/v1";
    public static final String FILE = "baseline.json";

    /** One rule of the baseline. {@code precision} and {@code recall} are null when they could not be computed. */
    public record Entry(String key, String sourceStatus, int truePositives, int falsePositives, int falseNegatives,
                        Double precision, Double recall) {
    }

    /** A verified rule whose precision fell. */
    public record Regression(String key, double baseline, double current, String currentCounts) {
    }

    /** Below this difference two precisions are equal (they are rounded to 4 decimals). */
    private static final double EPSILON = 1e-9;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static ListingEvalBaseline of(Result result) {
        List<Entry> entries = new ArrayList<>();
        for (RuleScore score : result.rules()) {
            entries.add(new Entry(score.key(), score.sourceStatus(), score.truePositives(), score.falsePositives(),
                    score.falseNegatives(), score.precision().value(), score.recall().value()));
        }
        return new ListingEvalBaseline(FORMAT, new TreeMap<>(result.rulesetVersions()), result.trademarkRulesetVersion(),
                result.cases(), List.copyOf(entries));
    }

    /** @throws IllegalArgumentException when the file is missing or not a baseline */
    public static ListingEvalBaseline load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No baseline at " + file + ". Write one with a real run: the eval-listing job "
                    + "with --update-baseline, or ./mvnw -pl api -am test -Dtest=ListingEvalGateTest -Deval.listing.update=true");
        }
        try {
            ListingEvalBaseline baseline = JSON.readValue(file.toFile(), ListingEvalBaseline.class);
            if (!FORMAT.equals(baseline.format())) {
                throw new IllegalArgumentException(file + " is not a " + FORMAT + " file (format " + baseline.format() + ").");
            }
            return baseline;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the baseline " + file + ": " + e.getMessage(), e);
        }
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writeValueAsString(this).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the baseline " + file + ": " + e.getMessage(), e);
        }
    }

    /** The verified rules of {@code result} whose precision is below this baseline's. */
    public List<Regression> regressions(Result result) {
        Map<String, Entry> byKey = new TreeMap<>();
        rules.forEach(entry -> byKey.put(entry.key(), entry));
        List<Regression> regressions = new ArrayList<>();
        for (RuleScore score : result.rules()) {
            if (!"VERIFIED".equals(score.sourceStatus())) {
                continue;
            }
            Entry before = byKey.get(score.key());
            Double current = score.precision().value();
            if (before == null || before.precision() == null || current == null) {
                continue;
            }
            if (current < before.precision() - EPSILON) {
                regressions.add(new Regression(score.key(), before.precision(), current, score.precision().text()));
            }
        }
        return List.copyOf(regressions);
    }
}
