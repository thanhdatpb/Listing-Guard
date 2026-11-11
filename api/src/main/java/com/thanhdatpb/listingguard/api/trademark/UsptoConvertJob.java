package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.ConversionReport;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoCatalogConverter;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConvertFilters;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts USPTO daily applications files inside {@code catalogs/raw/} to one {@code jsonl-1} file in the same
 * directory (ADR-009, PRs A2b and A2c): the latest record of each serial number, filtered. It touches no database and no
 * network. The output is written to a {@code .part} file and renamed when the conversion succeeded, so a failed run
 * never leaves a file that looks complete; next to it goes {@code <output>.report.json} with the files, the counts, the
 * SHA-256 of the output and the versions of the status map and the filters.
 *
 * <p>The inputs are files inside the raw directory, given one by one or as every {@code *.xml} file of one folder, and the
 * output is a plain file name inside it (the same rule as {@code file} in {@code catalogs/manifest.yaml}), so the job
 * cannot read or write anywhere else. An existing output is refused unless {@code overwrite} is true. With more than one
 * input the output name must be given.
 */
public final class UsptoConvertJob {

    /** What one run produced. */
    public record Result(ConversionReport report, Path output, Path reportFile) {
    }

    static final String REPORT_SUFFIX = ".report.json";
    private static final String PART_SUFFIX = ".part";
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final UsptoCatalogConverter converter;

    public UsptoConvertJob(UsptoStatusMap statusMap, UsptoConvertFilters filters, TextNormalizer normalizer,
                           UsptoCatalogConverter.Options options) {
        this.converter = new UsptoCatalogConverter(statusMap, filters, normalizer, options);
    }

    /**
     * @param rawDir    {@code catalogs/raw}
     * @param inputs    the XML files, relative to {@code rawDir} (for example {@code uspto/apc250101.xml}); empty when
     *                  {@code inputDir} is given
     * @param inputDir  a folder relative to {@code rawDir} whose {@code *.xml} files are all converted; null when
     *                  {@code inputs} are given
     * @param output    the plain file name of the output; null means the name of the one input with {@code .jsonl}
     * @param overwrite replace an existing output
     * @throws IllegalArgumentException if a path is not allowed, an input is missing or the output exists
     */
    public Result run(Path rawDir, List<String> inputs, String inputDir, String output, boolean overwrite)
            throws IOException {
        boolean hasInputs = inputs != null && !inputs.isEmpty();
        boolean hasDir = inputDir != null && !inputDir.isBlank();
        if (hasInputs == hasDir) {
            throw new IllegalArgumentException("Pass the files to convert as --input=<path inside catalogs/raw> (once "
                    + "for each file), for example --input=uspto/apc250101.xml, or as --input-dir=<folder inside "
                    + "catalogs/raw> for every .xml file of one folder, not both.");
        }
        Path root = rawDir.toAbsolutePath().normalize();
        List<Path> sources = hasInputs ? namedFiles(root, inputs) : folderFiles(root, inputDir);
        String name = output == null || output.isBlank() ? defaultName(sources, hasInputs) : output;
        if (!PLAIN_NAME.matcher(name).matches() || name.endsWith(PART_SUFFIX) || name.endsWith(REPORT_SUFFIX)) {
            throw new IllegalArgumentException("--output must be a plain file name ([A-Za-z0-9._-], at most 100 "
                    + "characters, not ending in " + PART_SUFFIX + " or " + REPORT_SUFFIX + "), got '" + name + "'.");
        }
        Path target = root.resolve(name);
        if (sources.contains(target)) {
            throw new IllegalArgumentException("--output must not be one of the input files.");
        }
        if (Files.exists(target) && !overwrite) {
            throw new IllegalArgumentException(name + " already exists in " + root
                    + "; pass --overwrite to replace it (a catalog that names it in the manifest then needs the new "
                    + "sha256).");
        }

        List<UsptoCatalogConverter.Source> converterSources = new ArrayList<>();
        for (Path source : sources) {
            converterSources.add(UsptoCatalogConverter.Source.file(
                    root.relativize(source).toString().replace('\\', '/'), source));
        }
        Path part = root.resolve(name + PART_SUFFIX);
        ConversionReport report;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), BUFFER_BYTES)) {
            report = converter.convert(converterSources, out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Path reportFile = root.resolve(name + REPORT_SUFFIX);
        Files.writeString(reportFile, report.toJson(), StandardCharsets.UTF_8);
        return new Result(report, target, reportFile);
    }

    private static List<Path> namedFiles(Path root, List<String> inputs) {
        List<Path> files = new ArrayList<>();
        for (String input : inputs) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("--input must name a file inside " + root + ".");
            }
            Path source = root.resolve(input).normalize();
            if (!source.startsWith(root) || source.equals(root)) {
                throw new IllegalArgumentException("--input must be a file inside " + root + ".");
            }
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("--input is not a file: " + root.relativize(source));
            }
            files.add(source);
        }
        return files;
    }

    private static List<Path> folderFiles(Path root, String inputDir) throws IOException {
        Path folder = root.resolve(inputDir).normalize();
        if (!folder.startsWith(root)) {
            throw new IllegalArgumentException("--input-dir must be a folder inside " + root + ".");
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("--input-dir is not a folder: " + root.relativize(folder));
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
                    .sorted().toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("--input-dir holds no .xml file: " + root.relativize(folder));
        }
        return files;
    }

    private static String defaultName(List<Path> sources, boolean named) {
        if (!named || sources.size() != 1) {
            throw new IllegalArgumentException("--output is required when more than one file is converted; name the "
                    + "catalog file, for example --output=uspto-2026-11.jsonl");
        }
        String file = sources.get(0).getFileName().toString();
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file) + ".jsonl";
    }
}
