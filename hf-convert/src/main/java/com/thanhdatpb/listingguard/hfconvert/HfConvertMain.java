package com.thanhdatpb.listingguard.hfconvert;

import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code hf-convert} job (ADR-030): {@code java -jar listing-guard-hf-convert.jar --input=hf --output=<name>.jsonl}.
 *
 * <p>Arguments (each may also come from an environment variable, the argument wins): {@code --input} (default
 * {@code hf}; a folder inside {@code catalogs/raw}, or {@code hf://datasets/<owner>/<name>[@<revision>]}),
 * {@code --output} (required), {@code --overwrite}, {@code --strict} ({@code HF_CONVERT_STRICT}),
 * {@code --allow-network} ({@code HF_CONVERT_ALLOW_NETWORK}, default false), {@code --filters} ({@code HF_FILTERS}:
 * {@code classpath:<path>} or {@code file:<path>}), {@code --min-year} ({@code HF_CONVERT_MIN_YEAR}),
 * {@code --memory-limit} ({@code HF_CONVERT_MEMORY_LIMIT}, default {@code 2GB}), {@code --threads}
 * ({@code HF_CONVERT_THREADS}, default 2), {@code --ruleset} ({@code HF_CONVERT_RULESET}, the trademark ruleset whose
 * normalization the import uses). The raw directory is {@code $CATALOGS_DIR/raw} ({@code catalogs/raw} by default).
 *
 * <p>Standard output gets the summary, the {@code sha256} and a manifest entry to paste; standard error the problems.
 * Exit code 0 when a file was written (also {@code INCOMPLETE}, which is said loudly), 1 when nothing was written.
 */
public final class HfConvertMain {

    static final String DEFAULT_RULESET = "classpath:rulesets/trademark-risk-2026-11b.yaml";
    private static final Set<String> FLAGS = Set.of("overwrite", "strict", "allow-network", "help");
    private static final Set<String> KEYS = Set.of("input", "output", "filters", "min-year", "memory-limit", "threads",
            "ruleset", "wordlist", "distinctiveness", "owner-columns");

    private HfConvertMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System::getenv, System.out, System.err));
    }

    /** The job without {@code System.exit}; {@code env} reads an environment variable (null when unset). */
    static int run(String[] args, Function<String, String> env, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = parse(args);
            if (options.containsKey("help")) {
                out.println(usage());
                return 0;
            }
            String input = option(options, env, "input", null, "hf");
            String output = option(options, env, "output", null, null);
            boolean overwrite = options.containsKey("overwrite");
            boolean strict = flag(options, env, "strict", "HF_CONVERT_STRICT");
            boolean network = flag(options, env, "allow-network", "HF_CONVERT_ALLOW_NETWORK");
            HfConvertFilters filters = filters(option(options, env, "filters", "HF_FILTERS",
                    "classpath:" + HfConvertFilters.DEFAULT_RESOURCE));
            String minYear = option(options, env, "min-year", "HF_CONVERT_MIN_YEAR", null);
            if (minYear != null) {
                filters = filters.withMinYear(parseInt("min-year", minYear));
            }
            String memory = option(options, env, "memory-limit", "HF_CONVERT_MEMORY_LIMIT", "2GB");
            int threads = parseInt("threads", option(options, env, "threads", "HF_CONVERT_THREADS", "2"));
            TrademarkRuleset ruleset = ruleset(option(options, env, "ruleset", "HF_CONVERT_RULESET", DEFAULT_RULESET));
            String catalogs = env.apply("CATALOGS_DIR");
            Path rawDir = Path.of(catalogs == null || catalogs.isBlank() ? "catalogs" : catalogs).resolve("raw");

            err.println("hf-convert: reading " + input + " with filters " + filters.version() + " (classes "
                    + filters.niceClasses() + ", min_year " + filters.minYear() + "), ruleset " + ruleset.version()
                    + ", network " + (network ? "ALLOWED" : "off") + ", DuckDB memory_limit " + memory + ", threads "
                    + threads + ".");
            HfConvertJob job = new HfConvertJob(filters, new TextNormalizer(ruleset.normalization()), ruleset.version(),
                    new HfConvertJob.Options(network, strict, memory, threads));
            HfConvertJob.Result result = job.run(rawDir, input, output, overwrite, options.get("wordlist"),
                    options.getOrDefault("distinctiveness", DistinctivenessThresholds.DEFAULT_VERSION), options.get("owner-columns"));
            summary(result, filters, out, err);
            return 0;
        } catch (IllegalArgumentException | HfConvertException e) {
            err.println("hf-convert: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("hf-convert: " + e);
            return 1;
        }
    }

    private static void summary(HfConvertJob.Result result, HfConvertFilters filters, PrintStream out,
                                PrintStream err) {
        HfConversionReport report = result.report();
        out.println("Converted " + report.input() + " into " + result.output().getFileName() + " in "
                + report.elapsedMillis() / 1000 + " s: " + report.caseFileRows() + " case_file rows, "
                + report.distinctSerials() + " serial numbers, " + report.recordsWritten() + " marks written, "
                + report.deadRemoved() + " not live, " + report.filteredTotal() + " filtered, " + report.recordErrors()
                + " record errors.");
        report.filtered().forEach((reason, count) -> out.println("  filtered " + reason + ": " + count));
        out.println("Owner data: " + report.distinctiveness().owners().state());
        out.println("Report: " + result.reportFile().getFileName());
        if (report.completeness() == Completeness.INCOMPLETE) {
            err.println("hf-convert: the conversion is INCOMPLETE; do not deploy it before you read the problems:");
            report.problems().forEach(problem -> err.println("  - " + problem));
        }
        out.println("sha256: " + report.outputSha256());
        out.println();
        out.println("A catalogs/manifest.yaml entry (choose the version label; source_key uspto gives source ids "
                + "uspto:<serial>, which the TSDR link and tsdr-status read):");
        out.println(manifestEntry(result, filters));
    }

    /** A manifest entry for the output, with the {@code sources:} form and the source key {@code uspto}. */
    static String manifestEntry(HfConvertJob.Result result, HfConvertFilters filters) {
        HfConversionReport report = result.report();
        HfConversionReport.Source source = report.source();
        String date = source.sourceDate() == null ? "<unknown: read the dataset's build date>" : source.sourceDate();
        String label = "hf-uspto-" + (source.sourceDate() == null ? "yyyy-mm" : source.sourceDate().substring(0, 7));
        List<String> lines = new ArrayList<>();
        lines.add("  - version: " + label);
        lines.add("    format_version: jsonl-2");
        lines.add("    sources:");
        lines.add("      - source_key: uspto");
        lines.add("        file: " + result.output().getFileName());
        lines.add("        source_name: " + quote(source.sourceName()));
        lines.add("        url: " + source.url());
        lines.add("        license: " + quote(source.license()));
        lines.add("        source_date: " + date);
        lines.add("        sha256: " + report.outputSha256());
        lines.add("    filters:");
        lines.add("      include_classes: " + filters.niceClasses());
        lines.add("      include_status: [REGISTERED, PENDING]");
        return String.join("\n", lines);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument '" + arg + "'.\n" + usage());
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            String key = eq < 0 ? body : body.substring(0, eq);
            if (eq < 0 ? !FLAGS.contains(key) : !KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown option '--" + key + "'.\n" + usage());
            }
            if (options.put(key, eq < 0 ? "" : body.substring(eq + 1)) != null) {
                throw new IllegalArgumentException("--" + key + " is given twice.");
            }
        }
        return options;
    }

    private static String option(Map<String, String> options, Function<String, String> env, String key,
                                 String variable, String fallback) {
        String value = options.get(key);
        if (value == null && variable != null) {
            value = env.apply(variable);
        }
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static boolean flag(Map<String, String> options, Function<String, String> env, String key,
                                String variable) {
        if (options.containsKey(key)) {
            return true;
        }
        String value = env.apply(variable);
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(variable + " must be true or false, got '" + value + "'.");
        };
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a whole number, got '" + value + "'.");
        }
    }

    private static HfConvertFilters filters(String location) {
        if (location.startsWith("classpath:")) {
            return HfConvertFilters.loadClasspath(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return HfConvertFilters.load(Path.of(location.substring("file:".length())));
        }
        throw new IllegalArgumentException("--filters must be classpath:<path> or file:<path>, got '" + location + "'.");
    }

    private static TrademarkRuleset ruleset(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            return TrademarkRulesetLoader.loadClasspath(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return TrademarkRulesetLoader.parse(Files.readString(Path.of(location.substring("file:".length())),
                    StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("--ruleset must be classpath:<path> or file:<path>, got '" + location + "'.");
    }

    static String usage() {
        return """
                Usage: hf-convert --output=<name>.jsonl [--wordlist=<file inside catalogs/raw>] [--input=hf | --input=hf://datasets/<owner>/<name>[@<rev>]]
                                  [--overwrite] [--strict] [--allow-network] [--min-year=<yyyy>]
                                  [--filters=classpath:<path>|file:<path>] [--memory-limit=2GB] [--threads=2]
                                  [--ruleset=classpath:<path>|file:<path>]
                                  [--distinctiveness=distinctiveness-2026-12|distinctiveness-2026-11] [--owner-columns=file:<path>]
                Reads $CATALOGS_DIR/raw/<input>/data/{case_file,intl_class,owner}/*.parquet (owner is optional) (plus profile/build_lineage.json and
                lookups/status_codes.csv) and writes $CATALOGS_DIR/raw/<name>.jsonl (jsonl-2) and <name>.jsonl.report.json.
                No network unless --allow-network (HF_CONVERT_ALLOW_NETWORK=true). See docs/trademark-catalog.md.""";
    }
}
