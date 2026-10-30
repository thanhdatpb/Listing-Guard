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
 */
public record BatchOptions(Path input, Path mapping, String client, String marketplace, Path out, Integer maxRows,
                           String labelColumn) {

    static final String USAGE = """
            Usage:
              --input=<file.csv> --mapping=<mapping.yaml> --client=<client name> --marketplace=ETSY|AMAZON
              [--out=<directory>] [--max-rows=<n>] [--label-column=<column>]""";

    private static final Set<String> KNOWN = Set.of("input", "mapping", "client", "marketplace", "out", "max-rows",
            "label-column");

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
        return new BatchOptions(Path.of(required(args, "input")), Path.of(required(args, "mapping")),
                required(args, "client"), required(args, "marketplace"), out == null ? defaultOut : Path.of(out),
                maxRows == null ? null : positive(maxRows), optional(args, "label-column"));
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
