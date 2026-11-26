package com.thanhdatpb.listingguard.hfconvert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the Parquet tables of the dataset are (ADR-030): a folder on disk laid out like the Hugging Face repository
 * ({@code data/case_file/*.parquet}, {@code data/intl_class/*.parquet}, optionally {@code profile/build_lineage.json} and
 * {@code lookups/status_codes.csv}), or the repository itself as {@code hf://datasets/<owner>/<name>[@<revision>]},
 * which needs the network and is refused unless the job allows it.
 *
 * <p>A local file is hashed (SHA-256) when the dataset is resolved, so the report names exactly what was read.
 */
public sealed interface HfDataset {

    String CASE_FILE = "case_file";
    String INTL_CLASS = "intl_class";
    String LINEAGE = "profile/build_lineage.json";
    String STATUS_LOOKUP = "lookups/status_codes.csv";

    /** {@code hf://datasets/<owner>/<name>} with an optional {@code @<revision>}; nothing after it. */
    Pattern REMOTE = Pattern.compile("hf://datasets/([A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95})"
            + "(?:@([A-Za-z0-9][A-Za-z0-9._-]{0,63}))?/?");

    /** One Parquet file: the path DuckDB reads, the name shown in the report, its size and SHA-256 (null if remote). */
    record ParquetFile(String table, String duckdbPath, String name, Long bytes, String sha256) {
    }

    /** The name shown in the report and the manifest: the folder inside {@code catalogs/raw}, or the URL. */
    String label();

    boolean remote();

    /** {@code <owner>/<name>} when known from the location (remote), else null. */
    String datasetId();

    /** The revision named in a remote URL, else null. */
    String revision();

    /** The DuckDB path of {@code relative} (a path inside the dataset, with forward slashes). */
    String pathOf(String relative);

    /** True when the location might be a URL, so the job must refuse it unless the network is allowed. */
    static boolean looksRemote(String location) {
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.contains("://") || lower.startsWith("hf:") || lower.startsWith("s3:") || lower.startsWith("http:")
                || lower.startsWith("https:") || lower.startsWith("gs:") || lower.startsWith("az:");
    }

    /** A folder on disk; the caller has checked that it is inside {@code catalogs/raw}. */
    record Local(Path dir, String label) implements HfDataset {

        @Override
        public boolean remote() {
            return false;
        }

        @Override
        public String datasetId() {
            return null;
        }

        @Override
        public String revision() {
            return null;
        }

        @Override
        public String pathOf(String relative) {
            return duckdbPath(dir.resolve(relative));
        }

        public boolean has(String relative) {
            return Files.isRegularFile(dir.resolve(relative));
        }

        /** The {@code *.parquet} files of a table, sorted by name, each hashed. */
        public List<ParquetFile> files(String table) throws IOException {
            return files(table, false);
        }

        List<ParquetFile> files(String table, boolean optional) throws IOException {
            Path folder = dir.resolve("data").resolve(table);
            if (optional && Files.notExists(folder)) {
                return List.of();
            }
            if (!Files.isDirectory(folder)) {
                throw new HfConvertException("The input folder " + label + " has no data/" + table + "/ folder; download "
                        + "data/" + table + "/*.parquet of the dataset there.");
            }
            List<Path> paths;
            try (Stream<Path> list = Files.list(folder)) {
                paths = list.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet"))
                        .sorted().toList();
            }
            if (paths.isEmpty() && !optional) {
                throw new HfConvertException("data/" + table + "/ of " + label + " holds no .parquet file.");
            }
            List<ParquetFile> files = new ArrayList<>();
            for (Path path : paths) {
                files.add(new ParquetFile(table, duckdbPath(path), "data/" + table + "/" + path.getFileName(),
                        Files.size(path), sha256(path)));
            }
            return files;
        }
    }

    /** The Hugging Face repository, read over the network by DuckDB's {@code httpfs} extension. */
    record Remote(String datasetId, String revision) implements HfDataset {

        public static Remote parse(String url) {
            Matcher matcher = REMOTE.matcher(url);
            if (!matcher.matches()) {
                throw new HfConvertException("A remote input must be hf://datasets/<owner>/<name>[@<revision>], got '"
                        + url + "'.");
            }
            return new Remote(matcher.group(1), matcher.group(2));
        }

        @Override
        public String label() {
            return "hf://datasets/" + datasetId + (revision == null ? "" : "@" + revision);
        }

        @Override
        public boolean remote() {
            return true;
        }

        @Override
        public String pathOf(String relative) {
            return label() + "/" + relative;
        }

        /** The glob of a table's files. */
        public String glob(String table) {
            return pathOf("data/" + table + "/*.parquet");
        }
    }

    /** An absolute path with forward slashes, which DuckDB reads on every system. */
    static String duckdbPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
