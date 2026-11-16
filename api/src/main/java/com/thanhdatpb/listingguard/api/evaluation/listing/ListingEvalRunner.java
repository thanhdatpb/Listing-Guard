package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.evaluation.EvaluationProperties;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.trademark.judge.TrademarkContextJudge;
import com.thanhdatpb.listingguard.api.evaluation.listing.ListingEvalBaseline.Regression;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=eval-listing [--update-baseline]} scores the active rulesets against the
 * labelled sample set of {@code eval/listing/cases/} (ADR-032), writes {@code eval/listing/out/report.md} and
 * {@code report.json}, and fails (non-zero exit) when a verified rule's precision is below {@code eval/listing/baseline.json}
 * or a case could not be analysed completely. {@code --update-baseline} writes the baseline from this run instead of comparing.
 *
 * <p>The trademark part is scanned with the DEMO catalog, never the served one, and nothing is written to the database. CI runs
 * the same comparison without Spring and without a database ({@code ListingEvalGateTest}).
 */
@Component
@Profile("eval-listing")
public class ListingEvalRunner implements ApplicationRunner {

    /** What a run gave. */
    public record Outcome(ListingEvaluator.Result result, List<Regression> regressions, boolean baselineWritten) {

        /** True when the gate fails: a regression, or a case whose analysis was not complete. */
        public boolean failed() {
            return !regressions.isEmpty() || !result.problems().isEmpty();
        }
    }

    private static final Set<String> KNOWN = Set.of("update-baseline", "llm-judge");

    private final EvaluationProperties properties;
    private final NormalizerRegistry normalizers;
    private final RuleEngine engine;
    private final ActiveRulesets rulesets;
    private final TrademarkRulesets trademarkRulesets;
    private final TrademarkContextJudge judge;

    public ListingEvalRunner(EvaluationProperties properties, NormalizerRegistry normalizers, RuleEngine engine,
                             ActiveRulesets rulesets, TrademarkRulesets trademarkRulesets, TrademarkContextJudge judge) {
        this.judge = judge;
        this.properties = properties;
        this.normalizers = normalizers;
        this.engine = engine;
        this.rulesets = rulesets;
        this.trademarkRulesets = trademarkRulesets;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String name : args.getOptionNames()) {
            if (!name.contains(".") && !KNOWN.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name + ". Usage: [--update-baseline] [--llm-judge]");
            }
        }
        boolean update = args.containsOption("update-baseline");
        Path dir = Path.of(properties.listingDir());
        Outcome outcome = evaluate(dir, Path.of(properties.demoCatalogDir()), normalizers::get, engine,
                marketplace -> rulesets.get(RulesetConfig.DEFAULT_PACK_ID, marketplace).config(), trademarkRulesets.ruleset(),
                dir.resolve("out"), update);
        print(outcome, dir, System.out);
        if (args.containsOption("llm-judge")) {
            compareWithJudge(dir, outcome);
        }
        if (outcome.failed()) {
            throw new IllegalStateException("eval-listing: the gate failed; see " + dir.resolve("out").resolve(
                    ListingEvalReport.MARKDOWN_FILE));
        }
    }

    /**
     * {@code --llm-judge} (ADR-036 item 11): the sample set again with the trademark context judge, written to
     * {@code <out>/llm-judge/}, and the agreement with the labelled trademark level before and after. The sample set is scanned with
     * the fictional DEMO catalog, so this proves the step works on labelled cases; the false-alarm and recall figures of real marks
     * come from the backtest.
     */
    private void compareWithJudge(Path dir, Outcome before) {
        if (!judge.active()) {
            throw new IllegalStateException("--llm-judge needs listing-guard.llm.enabled=true, listing-guard.llm.judge.enabled=true "
                    + "and a provider with listing-text-allowed=true (docs/llm.md).");
        }
        Outcome after = evaluate(dir, Path.of(properties.demoCatalogDir()), normalizers::get, engine,
                marketplace -> rulesets.get(RulesetConfig.DEFAULT_PACK_ID, marketplace).config(), trademarkRulesets.ruleset(),
                dir.resolve("out").resolve("llm-judge"), false, judge);
        System.out.println("eval-listing with the LLM judge: cases as labelled " + before.result().casesPassed() + " -> "
                + after.result().casesPassed() + " of " + after.result().cases() + "; trademark level agreement "
                + before.result().trademarkRiskAgreement().text() + " -> " + after.result().trademarkRiskAgreement().text() + ".");
    }

    /**
     * Loads the cases of {@code <listingDir>/cases}, scores them, compares with {@code <listingDir>/baseline.json} (or writes
     * it when {@code updateBaseline}) and writes the report to {@code outDir}. The Spring job and the CI test both call this.
     */
    public static Outcome evaluate(Path listingDir, Path demoCatalogDir, Function<String, Normalizer> normalizers,
                                   RuleEngine engine, Function<String, RulesetConfig> rulesets,
                                   TrademarkRuleset trademarkRuleset, Path outDir, boolean updateBaseline) {
        return evaluate(listingDir, demoCatalogDir, normalizers, engine, rulesets, trademarkRuleset, outDir, updateBaseline, null);
    }

    /** The same with a trademark context judge (ADR-036) applied to every scan; null is the evaluation as it always was. */
    public static Outcome evaluate(Path listingDir, Path demoCatalogDir, Function<String, Normalizer> normalizers,
                                   RuleEngine engine, Function<String, RulesetConfig> rulesets,
                                   TrademarkRuleset trademarkRuleset, Path outDir, boolean updateBaseline,
                                   TrademarkContextJudge judge) {
        TrademarkScanner scanner = DemoCatalogScanner.load(trademarkRuleset, demoCatalogDir);
        ListingAnalyzer analyzer = new ListingAnalyzer(normalizers, engine, rulesets, marketplace -> scanner);
        if (judge != null) {
            analyzer = analyzer.withJudge(judge);
        }
        ListingEvaluator.Result result = new ListingEvaluator(analyzer, rulesets, scanner)
                .evaluate(ListingEvalCase.loadAll(listingDir.resolve("cases")));
        Path baselineFile = listingDir.resolve(ListingEvalBaseline.FILE);
        List<Regression> regressions;
        if (updateBaseline) {
            ListingEvalBaseline.of(result).write(baselineFile);
            regressions = List.of();
        } else {
            regressions = ListingEvalBaseline.load(baselineFile).regressions(result);
        }
        ListingEvalReport.write(outDir, result, regressions);
        return new Outcome(result, regressions, updateBaseline);
    }

    static void print(Outcome outcome, Path dir, PrintStream out) {
        ListingEvaluator.Result result = outcome.result();
        out.println("eval-listing: " + result.cases() + " cases, " + result.casesPassed() + " as labelled, "
                + outcome.regressions().size() + " precision regression(s) of a verified rule, "
                + result.problems().size() + " problem(s)."
                + (outcome.baselineWritten() ? " Baseline written to " + dir.resolve(ListingEvalBaseline.FILE) + "." : ""));
    }
}
