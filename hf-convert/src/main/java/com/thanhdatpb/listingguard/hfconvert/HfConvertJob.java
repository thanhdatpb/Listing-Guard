package com.thanhdatpb.listingguard.hfconvert;

import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts the dataset to one {@code jsonl-2} file inside {@code catalogs/raw/} (ADR-030), like {@code uspto-convert}:
 * the input is a folder inside the raw directory (or a {@code hf://} URL when the network is allowed), the output a plain
 * file name inside it, written to a {@code .part} file and renamed when the conversion succeeded, with
 * {@code <output>.report.json} next to it. An existing output is refused unless {@code overwrite} is true. DuckDB spills
 * to a fresh folder {@code .hf-convert-tmp/} of the raw directory, removed at the end.
 */
public final class HfConvertJob {

    /** What one run produced. */
    public record Result(HfConversionReport report, Path output, Path reportFile) {
    }

    /** The settings of a run but the temporary folder, which the job makes. */
    public record Options(boolean allowNetwork, boolean strict, String memoryLimit, int threads) {
    }

    static final String REPORT_SUFFIX = ".report.json";
    static final String TEMP_DIR = ".hf-convert-tmp";
    private static final String PART_SUFFIX = ".part";
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final HfConvertFilters filters;
    private final TextNormalizer normalizer;
    private final String rulesetVersion;
    private final Options options;

    public HfConvertJob(HfConvertFilters filters, TextNormalizer normalizer, String rulesetVersion, Options options) {
        this.filters = filters;
        this.normalizer = normalizer;
        this.rulesetVersion = rulesetVersion;
        this.options = options;
    }

    /**
     * @param rawDir    {@code catalogs/raw}
     * @param input     a folder inside {@code rawDir} (for example {@code hf}), or {@code hf://datasets/<owner>/<name>}
     * @param output    the plain file name of the output, for example {@code hf-uspto-2026-10.jsonl}
     * @param overwrite replace an existing output
     * @throws IllegalArgumentException if a path is not allowed or the output exists
     * @throws HfConvertException       if the conversion fails
     */
    public Result run(Path rawDir, String input, String output, boolean overwrite) throws IOException {
        return run(rawDir, input, output, overwrite, null);
    }

    Result run(Path rawDir, String input, String output, boolean overwrite, String wordlist) throws IOException {
        Path root = rawDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("The raw directory " + root + " does not exist.");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Pass the dataset as --input=<folder inside catalogs/raw>, for example "
                    + "--input=hf, or --input=hf://datasets/" + filters.dataset() + " with --allow-network.");
        }
        HfDataset dataset = HfDataset.looksRemote(input) ? HfDataset.Remote.parse(input) : local(root, input);
        if (output == null || output.isBlank() || !PLAIN_NAME.matcher(output).matches() || output.endsWith(PART_SUFFIX)
                || output.endsWith(REPORT_SUFFIX)) {
            throw new IllegalArgumentException("--output must be a plain file name ([A-Za-z0-9._-], at most 100 "
                    + "characters, not ending in " + PART_SUFFIX + " or " + REPORT_SUFFIX + "), for example "
                    + "--output=hf-uspto-2026-10.jsonl; got '" + output + "'.");
        }
        Path target = root.resolve(output);
        if (Files.exists(target) && !overwrite) {
            throw new IllegalArgumentException(output + " already exists in " + root + "; pass --overwrite to replace it "
                    + "(a catalog that names it in the manifest then needs the new sha256).");
        }

        Path dictionary = null;
        if (wordlist != null) {
            Path path = root.resolve(wordlist).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)
                    || !path.toRealPath().startsWith(root.toRealPath()) || Files.size(path) > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("--wordlist must be a regular file of at most 16 MiB inside catalogs/raw.");
            }
            if (path.equals(target) || path.equals(root.resolve(output + REPORT_SUFFIX))
                    || path.startsWith(root.resolve(TEMP_DIR))) {
                throw new IllegalArgumentException("--wordlist must be separate from output and temporary files.");
            }
            dictionary = path;
        }

        Path temp = root.resolve(TEMP_DIR);
        deleteTree(temp);
        Files.createDirectories(temp);
        Path part = root.resolve(output + PART_SUFFIX);
        HfConversionReport report;
        try {
            HfCatalogConverter converter = new HfCatalogConverter(filters, normalizer, rulesetVersion,
                    new HfCatalogConverter.Settings(options.allowNetwork(), options.strict(), options.memoryLimit(),
                            options.threads(), temp));
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), BUFFER_BYTES)) {
                report = converter.convert(dataset, out, dictionary);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(part);
                throw e;
            }
        } finally {
            deleteTree(temp);
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Path reportFile = root.resolve(output + REPORT_SUFFIX);
        Files.writeString(reportFile, report.toJson(), StandardCharsets.UTF_8);
        return new Result(report, target, reportFile);
    }

    private static HfDataset.Local local(Path root, String input) {
        Path folder = root.resolve(input).normalize();
        if (!folder.startsWith(root) || folder.equals(root)) {
            throw new IllegalArgumentException("--input must be a folder inside " + root + ", for example --input=hf.");
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("--input is not a folder: " + root.relativize(folder)
                    + ". Download the dataset there first (docs/trademark-catalog.md, \"Hugging Face source\").");
        }
        return new HfDataset.Local(folder, root.relativize(folder).toString().replace('\\', '/'));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
