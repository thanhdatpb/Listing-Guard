package com.thanhdatpb.listingguard.api.batch;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

/**
 * The command line of the batch job.
 *
 * @param out        the directory the reports are written to
 * @param maxRows    stop after this many data rows, or null for all
 * @param labelColumn the column holding a true/false label (for example "was taken down") to measure against, or null
 * @param splitOutput also write {@code passed.csv} and {@code held.csv} (see {@link SplitOutput}); without it the output
 *                    is what it always was
 * @param holdTrademarkRisk the trademark level at which a listing is held back, the choice of the person who runs the batch;
 *                    used only with {@code splitOutput}, {@code HIGH} by default
 */
public record BatchOptions(Path input, Path mapping, String client, String marketplace, Path out, Integer maxRows,
                           String labelColumn, boolean splitOutput, SplitOutput.TrademarkHold holdTrademarkRisk) {

    static final String USAGE = """
            Usage:
              --input=<file.csv> --mapping=<mapping.yaml> --client=<client name> --marketplace=ETSY|AMAZON
              [--out=<directory>] [--max-rows=<n>] [--label-column=<column>]
              [--split-output [--hold-trademark-risk=HIGH|MEDIUM|NONE]]""";

    private static final Set<String> KNOWN = Set.of("input", "mapping", "client", "marketplace", "out", "max-rows",
            "label-column", "split-output", "hold-trademark-risk");

    /** The options of a run without the review gate: the output is the three reports only. */
    public BatchOptions(Path input, Path mapping, String client, String marketplace, Path out, Integer maxRows,
                        String labelColumn) {
        this(input, mapping, client, marketplace, out, maxRows, labelColumn, false, SplitOutput.TrademarkHold.HIGH);
    }

    /**
     * @param defaultOut the directory used when {@code --out} is not given
     * @throws BatchConfigException for a missing, repeated, unknown or invalid option
     */
    static BatchOptions parse(ApplicationArguments args, Path defaultOut) {
        for (String name : args.getOptionNames()) {
            // names with a dot are Spring's own settings, passed the same way (--spring.profiles.active=...)
            if (!name.contains(".") && !KNOWN.contains(name)) {
                throw usage("Unknown option --" + name + ".");
            }
        }
        String out = optional(args, "out");
        String maxRows = optional(args, "max-rows");
        boolean split = flag(args, "split-output");
        String hold = optional(args, "hold-trademark-risk");
        if (hold != null && !split) {
            throw usage("--hold-trademark-risk needs --split-output.");
        }
        return new BatchOptions(Path.of(required(args, "input")), Path.of(required(args, "mapping")),
                required(args, "client"), required(args, "marketplace"), out == null ? defaultOut : Path.of(out),
                maxRows == null ? null : positive(maxRows), optional(args, "label-column"), split,
                hold == null ? SplitOutput.TrademarkHold.HIGH : SplitOutput.TrademarkHold.parse(hold));
    }

    /** An option with no value, such as {@code --split-output}. */
    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return false;
        }
        if (!values.isEmpty()) {
            throw usage("--" + name + " takes no value.");
        }
        return true;
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

    private static BatchConfigException usage(String problem) {
        return new BatchConfigException(problem + "\n" + USAGE);
    }
}
