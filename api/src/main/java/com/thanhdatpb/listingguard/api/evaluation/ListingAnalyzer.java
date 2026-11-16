package com.thanhdatpb.listingguard.api.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.judge.TrademarkContextJudge;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import java.util.Locale;
import java.util.function.Function;

/**
 * Runs the listing rules and the trademark scan on one listing for an evaluation (ADR-032): the backtest of labelled
 * listings and the labelled sample set. It uses the same pieces as {@code POST /api/v1/checks} and
 * {@code POST /api/v1/trademark-checks} (the marketplace's normalizer, the rule engine with the active ruleset, the scanner
 * and the scanner input that {@link TrademarkCheckService} builds), but it <b>stores nothing, charges nothing and reads no
 * cache</b>: the listings of an evaluation are not a client's checks, and a claim file must not end up in the database.
 *
 * <p>The two parts are independent, as in the batch job: a scanner that is not served, or a part that throws, leaves the
 * other part's result.
 */
public final class ListingAnalyzer {

    /** Gives the scanner a marketplace is served with. */
    @FunctionalInterface
    public interface ScannerLookup {

        /** @throws TrademarkUnavailableException when no trademark catalog is served */
        TrademarkScanner scanner(String marketplace);
    }

    /** How one part of an analysis ended. */
    public enum PartState {
        /** the part ran and has a result */
        DONE,
        /** the part cannot run here (a trademark catalog that is not served) */
        NOT_CONFIGURED,
        /** the part threw; the message says what */
        FAILED
    }

    /**
     * What an analysis found.
     *
     * @param rules          the rule run, null unless {@code rulesState} is DONE
     * @param rulesMessage   why the rules did not run, null when they did
     * @param trademark      the scan result, null unless {@code trademarkState} is DONE
     * @param trademarkMessage why the scan did not run, null when it did
     * @param scanner        the scanner that ran (for a caller that explains the result), null when none ran
     * @param input          the scanner input built from the normalized listing
     */
    public record Analysis(String marketplace, String rulesetVersion, Listing listing, PartState rulesState, RuleRun rules,
                           String rulesMessage, PartState trademarkState, TrademarkResult trademark,
                           String trademarkMessage, TrademarkScanner scanner, TrademarkCheckInput input) {
    }

    private static final int MESSAGE_MAX_CHARS = 200;

    private final Function<String, Normalizer> normalizers;
    private final RuleEngine engine;
    private final Function<String, RulesetConfig> rulesets;
    private final ScannerLookup scanners;
    private final TrademarkContextJudge judge;

    /**
     * @param normalizers the normalizer of a marketplace code
     * @param rulesets    the active {@code listing-rules} ruleset of a marketplace code (upper case)
     * @param scanners    the trademark scanner of a marketplace code (upper case)
     */
    public ListingAnalyzer(Function<String, Normalizer> normalizers, RuleEngine engine,
                           Function<String, RulesetConfig> rulesets, ScannerLookup scanners) {
        this(normalizers, engine, rulesets, scanners, null);
    }

    private ListingAnalyzer(Function<String, Normalizer> normalizers, RuleEngine engine,
                            Function<String, RulesetConfig> rulesets, ScannerLookup scanners, TrademarkContextJudge judge) {
        this.judge = judge;
        this.normalizers = normalizers;
        this.engine = engine;
        this.rulesets = rulesets;
        this.scanners = scanners;
    }

    /** The same analysis with the trademark context judge (ADR-036) applied to the scan result; used by {@code --llm-judge}. */
    public ListingAnalyzer withJudge(TrademarkContextJudge contextJudge) {
        return new ListingAnalyzer(normalizers, engine, rulesets, scanners, contextJudge);
    }

    /**
     * @param listing the standard input of {@code docs/input-schema.md}
     * @throws IllegalArgumentException when the listing cannot be normalized (it is not a JSON object)
     */
    public Analysis analyze(String marketplace, JsonNode listing) {
        Normalizer normalizer = normalizers.apply(marketplace);
        Listing normalized = normalizer.normalize(listing);
        String code = normalizer.marketplace().toUpperCase(Locale.ROOT);
        RulesetConfig ruleset = rulesets.apply(code);

        RuleRun run = null;
        PartState rulesState;
        String rulesMessage = null;
        try {
            // runDetailed, not run: it says which rules failed, so a run with a failure is never read as clean
            run = engine.runDetailed(normalized, ruleset);
            rulesState = PartState.DONE;
        } catch (RuntimeException e) {
            rulesState = PartState.FAILED;
            rulesMessage = describe(e);
        }

        TrademarkCheckInput input = TrademarkCheckService.input(normalized, null);
        TrademarkScanner scanner = null;
        TrademarkResult result = null;
        PartState trademarkState;
        String trademarkMessage = null;
        try {
            scanner = scanners.scanner(code);
            result = scanner.scan(input);
            if (judge != null) {
                // the same step a stored check takes (ADR-036), only for a measurement run that asked for it
                result = judge.judge(input, result, scanner.catalog());
            }
            trademarkState = PartState.DONE;
        } catch (TrademarkUnavailableException e) {
            trademarkState = PartState.NOT_CONFIGURED;
            trademarkMessage = clip(e.getMessage());
            scanner = null;
        } catch (RuntimeException e) {
            trademarkState = PartState.FAILED;
            trademarkMessage = describe(e);
        }
        return new Analysis(code, ruleset.version(), normalized, rulesState, run, rulesMessage, trademarkState, result,
                trademarkMessage, scanner, input);
    }

    /** The kind of failure and the first line of its message, cut: it goes into a report. */
    static String describe(RuntimeException e) {
        String message = clip(e.getMessage());
        return e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        return line.length() <= MESSAGE_MAX_CHARS ? line : line.substring(0, MESSAGE_MAX_CHARS) + "...";
    }
}
