package com.thanhdatpb.listingguard.api.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.ConversionReport;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoDailyConverter;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Converts one USPTO daily applications file inside {@code catalogs/raw/} to {@code jsonl-1} in the same directory
 * (ADR-009, PR A2b). It touches no database and no network. The output is written to a {@code .part} file and renamed
 * when the conversion succeeded, so a failed run never leaves a file that looks complete; next to it goes
 * {@code <output>.report.json} with the counts, the SHA-256 of input and output and the version of the status map.
 *
 * <p>The input must be a file inside the raw directory and the output a plain file name inside it (the same rule as
 * {@code file} in {@code catalogs/manifest.yaml}), so the job cannot read or write anywhere else. An existing output is
 * refused unless {@code overwrite} is true.
 */
public final class UsptoConvertJob {

    /** What one run produced. */
    public record Result(ConversionReport report, Path output, Path reportFile) {
    }

    static final String REPORT_SUFFIX = ".report.json";
    private static final String PART_SUFFIX = ".part";
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final UsptoDailyConverter converter;

    public UsptoConvertJob(UsptoStatusMap statusMap, TextNormalizer normalizer, boolean allowOtherVersion) {
        this.converter = new UsptoDailyConverter(statusMap, normalizer, allowOtherVersion);
    }

    /**
     * @param rawDir    {@code catalogs/raw}
     * @param input     the XML file, relative to {@code rawDir} (for example {@code uspto/daily-20241101.xml})
     * @param output    the plain file name of the output; null means the name of the input with {@code .jsonl}
     * @param overwrite replace an existing output
     * @throws IllegalArgumentException if a path is not allowed, the input is missing or the output exists
     */
    public Result run(Path rawDir, String input, String output, boolean overwrite) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Pass the file to convert as --input=<path inside catalogs/raw>, for "
                    + "example --input=uspto/daily-20241101.xml");
        }
        Path root = rawDir.toAbsolutePath().normalize();
        Path source = root.resolve(input).normalize();
        if (!source.startsWith(root) || source.equals(root)) {
            throw new IllegalArgumentException("--input must be a file inside " + root + ".");
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("--input is not a file: " + root.relativize(source));
        }
        String name = output == null || output.isBlank() ? defaultName(source) : output;
        if (!PLAIN_NAME.matcher(name).matches() || name.endsWith(PART_SUFFIX) || name.endsWith(REPORT_SUFFIX)) {
            throw new IllegalArgumentException("--output must be a plain file name ([A-Za-z0-9._-], at most 100 "
                    + "characters, not ending in " + PART_SUFFIX + " or " + REPORT_SUFFIX + "), got '" + name + "'.");
        }
        Path target = root.resolve(name);
        if (target.equals(source)) {
            throw new IllegalArgumentException("--output must not be the input file.");
        }
        if (Files.exists(target) && !overwrite) {
            throw new IllegalArgumentException(name + " already exists in " + root
                    + "; pass --overwrite to replace it (a catalog that names it in the manifest then needs the new "
                    + "sha256).");
        }

        Path part = root.resolve(name + PART_SUFFIX);
        ConversionReport report;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source), BUFFER_BYTES);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), BUFFER_BYTES)) {
            report = converter.convert(in, out);
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

    private static String defaultName(Path source) {
        String file = source.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file) + ".jsonl";
    }
}
