package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.evaluation.EvaluationProperties;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.trademark.judge.TrademarkContextJudge;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=backtest --input=... --mapping=... --marketplace=... --claimed-column=...}
 * measures the checks against a file of labelled listings (see {@link BacktestJob}, ADR-032 and {@code docs/backtest.md}),
 * then the application exits (the profile turns the web server off). It reads local files and the database (the served
 * trademark catalog), writes nothing to the database and makes no network call. A refused option, mapping or input file makes
 * the process exit with a non-zero code.
 */
@Component
@Profile("backtest")
public class BacktestRunner implements ApplicationRunner {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final EvaluationProperties properties;
    private final NormalizerRegistry normalizers;
    private final RuleEngine engine;
    private final ActiveRulesets rulesets;
    private final TrademarkCatalogRuntime trademark;
    private final TrademarkContextJudge judge;

    public BacktestRunner(EvaluationProperties properties, NormalizerRegistry normalizers, RuleEngine engine,
                          ActiveRulesets rulesets, TrademarkCatalogRuntime trademark, TrademarkContextJudge judge) {
        this.judge = judge;
        this.properties = properties;
        this.normalizers = normalizers;
        this.engine = engine;
        this.rulesets = rulesets;
        this.trademark = trademark;
    }

    @Override
    public void run(ApplicationArguments args) {
        Clock clock = Clock.systemUTC();
        BacktestOptions options = BacktestOptions.parse(args,
                properties.backtestOut().resolve(STAMP.format(Instant.now(clock))));
        ListingAnalyzer analyzer = new ListingAnalyzer(normalizers::get, engine, this::ruleset,
                marketplace -> trademark.serving(marketplace).scanner());
        BacktestJob.Result before = new BacktestJob(analyzer, this::ruleset, this::served, System.out, clock).run(options);
        if (args.containsOption("llm-judge")) {
            compareWithJudge(analyzer, options, before, clock);
        }
    }

    /**
     * {@code --llm-judge} (ADR-036 item 11): the same file again with the trademark context judge applied, written to
     * {@code <out>/llm-judge/}, and one comparison of recall of the claimed rows and of the share of unclaimed rows flagged. It
     * needs {@code listing-guard.llm.enabled}, {@code listing-guard.llm.judge.enabled} and a provider allowed to see listing text.
     */
    private void compareWithJudge(ListingAnalyzer analyzer, BacktestOptions options, BacktestJob.Result before, Clock clock) {
        if (!judge.active()) {
            throw new IllegalStateException("--llm-judge needs listing-guard.llm.enabled=true, listing-guard.llm.judge.enabled=true "
                    + "and a provider with listing-text-allowed=true (docs/llm.md).");
        }
        BacktestJob.Result after = new BacktestJob(analyzer.withJudge(judge), this::ruleset, this::served, System.out, clock)
                .run(options.withOut(options.out().resolve("llm-judge")));
        String lost = after.caught() < before.caught() ? "RECALL FELL: the judge must stay off by default." : "Recall did not fall.";
        String text = String.join(System.lineSeparator(), "LLM judge, before and after (ADR-036)",
                "recall of claimed rows: before " + before.recall().text() + ", after " + after.recall().text(),
                "claimed rows caught: before " + before.caught() + ", after " + after.caught() + " (lost "
                        + (before.caught() - after.caught()) + ")",
                "unclaimed rows flagged: before " + before.unclaimedFlagged().text() + ", after "
                        + after.unclaimedFlagged().text(),
                lost, "");
        System.out.print(text);
        try {
            java.nio.file.Files.writeString(options.out().resolve("llm-judge-comparison.txt"), text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private RulesetConfig ruleset(String marketplace) {
        return rulesets.get(RulesetConfig.DEFAULT_PACK_ID, marketplace).config();
    }

    private BacktestJob.TrademarkInfo served(String marketplace) {
        TrademarkCatalogRuntime.Status status = trademark.status();
        if (status.state() != TrademarkCatalogRuntime.State.READY) {
            return new BacktestJob.TrademarkInfo(status.state().name(), null, null, status.detail());
        }
        TrademarkCatalogRuntime.Serving serving = trademark.serving(marketplace);
        return new BacktestJob.TrademarkInfo(status.state().name(), serving.catalogVersion(),
                serving.scanner().ruleset().version(), null);
    }
}
