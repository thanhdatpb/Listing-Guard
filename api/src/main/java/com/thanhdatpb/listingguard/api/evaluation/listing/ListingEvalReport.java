package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvalBaseline.Regression;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.CaseResult;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.Result;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvaluator.RuleScore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes the result of the labelled sample set as {@code report.md} and {@code report.json} (ADR-032). The cases are
 * synthetic, so the report may name the findings of each case; it is in English because it goes into the CI job summary.
 */
public final class ListingEvalReport {

    public static final String MARKDOWN_FILE = "report.md";
    public static final String JSON_FILE = "report.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The JSON file: the result and the regressions against the baseline. */
    record Written(Result result, List<Regression> regressions) {
    }

    private ListingEvalReport() {
    }

    public static void write(Path dir, Result result, List<Regression> regressions) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(JSON_FILE), JSON.writeValueAsString(new Written(result, regressions)) + "\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(MARKDOWN_FILE), markdown(result, regressions), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the eval-listing report to " + dir + ": " + e.getMessage(), e);
        }
    }

    public static String markdown(Result result, List<Regression> regressions) {
        StringBuilder md = new StringBuilder();
        md.append("## Labelled listing evaluation (eval-listing)\n\n");
        md.append("Rulesets ").append(result.rulesetVersions()).append(", trademark ruleset ")
                .append(result.trademarkRulesetVersion()).append(" with the DEMO catalog `").append(result.trademarkCatalogVersion())
                .append("`. ").append(result.cases()).append(" synthetic cases, ").append(result.casesPassed())
                .append(" with exactly the expected findings. Trademark level as expected: ")
                .append(result.trademarkRiskAgreement().text()).append(".\n\n");
        if (regressions.isEmpty()) {
            md.append("**Gate: passed.** No verified rule has a lower precision than the baseline.\n\n");
        } else {
            md.append("**Gate: FAILED.** Precision fell for a verified rule:\n\n| Rule | Baseline | Now |\n|---|---:|---|\n");
            for (Regression regression : regressions) {
                md.append("| ").append(regression.key()).append(" | ")
                        .append(String.format(Locale.ROOT, "%.4f", regression.baseline())).append(" | ")
                        .append(regression.currentCounts()).append(" |\n");
            }
            md.append('\n');
        }
        if (!result.problems().isEmpty()) {
            md.append("**Problems** (a case whose analysis was not complete fails the gate):\n\n");
            result.problems().forEach(problem -> md.append("- ").append(problem).append('\n'));
            md.append('\n');
        }
        md.append("| Rule | Source | TP | FP | FN | Precision | Recall |\n|---|---|---:|---:|---:|---|---|\n");
        for (RuleScore score : result.rules()) {
            md.append("| ").append(score.key()).append(" | ").append(score.sourceStatus().toLowerCase(Locale.ROOT))
                    .append(" | ").append(score.truePositives()).append(" | ").append(score.falsePositives()).append(" | ")
                    .append(score.falseNegatives()).append(" | ").append(score.precision().text()).append(" | ")
                    .append(score.recall().text()).append(" |\n");
        }
        md.append('\n');
        if (!result.verifiedRulesWithoutCase().isEmpty()) {
            md.append("Verified rules no case exercises (not gated until a case does): ")
                    .append(String.join(", ", result.verifiedRulesWithoutCase())).append(".\n\n");
        }
        List<CaseResult> failing = result.caseResults().stream().filter(c -> !c.passed()).toList();
        if (!failing.isEmpty()) {
            md.append("Cases that differ from their labels:\n\n");
            for (CaseResult c : failing) {
                md.append("- `").append(c.id()).append("`");
                if (!c.falseNegatives().isEmpty()) {
                    md.append(" missed ").append(c.falseNegatives());
                }
                if (!c.falsePositives().isEmpty()) {
                    md.append(" unexpected ").append(c.falsePositives());
                }
                if (c.expectedTrademarkRisk() != null && !c.expectedTrademarkRisk().equals(c.trademarkRisk())) {
                    md.append(" trademark ").append(c.trademarkRisk()).append(" (expected ").append(c.expectedTrademarkRisk())
                            .append(')');
                }
                if (c.problem() != null) {
                    md.append(" problem: ").append(c.problem());
                }
                md.append('\n');
            }
            md.append('\n');
        }
        md.append("The cases are self-written and synthetic; they measure that the rules do what their labels say, not how the "
                + "checks do on real listings (that is the backtest, docs/backtest.md).\n");
        return md.toString();
    }
}
