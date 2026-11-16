package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.batch.BatchConfigException;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

/**
 * The command line of the backtest job (ADR-032).
 *
 * @param out                the directory the reports are written to
 * @param maxRows            stop after this many data rows, or null for all
 * @param claimedColumn      the column holding yes/no: was the listing claimed or taken down
 * @param issueColumn        the column holding what the claim was about ({@link IssueType}), or null
 * @param markColumn         the column holding the mark the claim named, or null
 * @param catchSeverity      a rule finding at this severity or above counts as caught
 * @param catchTrademarkRisk a trademark level at this level or above counts as caught
 * @param variantThreshold   how close a word of the listing must be to the labelled mark for a miss to be called a spelling
 *                           variant; it only explains a miss and never changes a finding
 */
public record BacktestOptions(Path input, Path mapping, String marketplace, String claimedColumn, String issueColumn,
                              String markColumn, Path out, Integer maxRows, Severity catchSeverity,
                              RiskLevel catchTrademarkRisk, double variantThreshold) {

    static final Severity DEFAULT_CATCH_SEVERITY = Severity.WARNING;
    static final RiskLevel DEFAULT_CATCH_TRADEMARK_RISK = RiskLevel.MEDIUM;
    static final double DEFAULT_VARIANT_THRESHOLD = 0.6;

    static final String USAGE = """
            Usage:
              --input=<file.csv> --mapping=<mapping.yaml> --marketplace=ETSY|AMAZON --claimed-column=<column>
              [--issue-column=<column>] [--mark-column=<column>] [--out=<directory>] [--max-rows=<n>]
              [--catch-severity=ERROR|WARNING|INFO] [--catch-trademark-risk=MEDIUM|HIGH] [--variant-threshold=<0..1>]""";

    private static final Set<String> KNOWN = Set.of("input", "mapping", "marketplace", "claimed-column", "issue-column",
            "mark-column", "out", "max-rows", "catch-severity", "catch-trademark-risk", "variant-threshold");

    /**
     * @param defaultOut the directory used when {@code --out} is not given
     * @throws BatchConfigException for a missing, repeated, unknown or invalid option
     */
    static BacktestOptions parse(ApplicationArguments args, Path defaultOut) {
        for (String name : args.getOptionNames()) {
            // names with a dot are Spring's own settings, passed the same way (--spring.profiles.active=...)
            if (!name.contains(".") && !KNOWN.contains(name)) {
                throw usage("Unknown option --" + name + ".");
            }
        }
        String out = optional(args, "out");
        String maxRows = optional(args, "max-rows");
        String severity = optional(args, "catch-severity");
        String risk = optional(args, "catch-trademark-risk");
        String threshold = optional(args, "variant-threshold");
        return new BacktestOptions(Path.of(required(args, "input")), Path.of(required(args, "mapping")),
                required(args, "marketplace"), required(args, "claimed-column"), optional(args, "issue-column"),
                optional(args, "mark-column"), out == null ? defaultOut : Path.of(out),
                maxRows == null ? null : positive(maxRows), severity == null ? DEFAULT_CATCH_SEVERITY : severity(severity),
                risk == null ? DEFAULT_CATCH_TRADEMARK_RISK : risk(risk),
                threshold == null ? DEFAULT_VARIANT_THRESHOLD : threshold(threshold));
    }

    private static String required(ApplicationArguments args, String name) {
        String value = optional(args, name);
        if (value == null) {
            throw usage("Missing --" + name + ".");
        }
        return value;
    }

    private static String optional(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            throw usage("Pass --" + name + " once.");
        }
        String value = values.get(0).trim();
        if (value.isEmpty()) {
            throw usage("--" + name + " is empty.");
        }
        return value;
    }

    private static int positive(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw usage("--max-rows must be a whole number, 1 or more; got '" + text + "'.");
    }

    private static Severity severity(String text) {
        try {
            return Severity.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw usage("--catch-severity must be ERROR, WARNING or INFO; got '" + text + "'.");
        }
    }

    private static RiskLevel risk(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.equals("MEDIUM") || upper.equals("HIGH")) {
            return RiskLevel.valueOf(upper);
        }
        throw usage("--catch-trademark-risk must be MEDIUM or HIGH; got '" + text + "'.");
    }

    private static double threshold(String text) {
        try {
            double value = Double.parseDouble(text);
            if (value > 0 && value < 1) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw usage("--variant-threshold must be a number between 0 and 1 (both excluded); got '" + text + "'.");
    }

    private static BatchConfigException usage(String problem) {
        return new BatchConfigException(problem + "\n" + USAGE);
    }
}
