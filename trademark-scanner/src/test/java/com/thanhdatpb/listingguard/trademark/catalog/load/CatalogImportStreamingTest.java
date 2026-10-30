package com.thanhdatpb.listingguard.trademark.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data file is read as a stream (ADR-009, PR A2b). Synthetic marks only. */
class CatalogImportStreamingTest {

    private static final int LINES = 200_000;
    private static final int HEAP_MEGABYTES = 32;

    private final TextNormalizer normalizer = new TextNormalizer(TestRulesets.test().normalization());

    @TempDir
    Path raw;

    private CatalogManifest.Entry entry(String file, String sha) {
        return CatalogManifest.Entry.single("stream-1", file, CatalogImport.FORMAT_VERSION, "Synthetic",
                "https://example.invalid/stream", "test only", LocalDate.of(2026, 10, 30), sha, Set.of(25, 35),
                Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING));
    }

    private CatalogManifest.Entry write(String content) throws IOException {
        Files.writeString(raw.resolve("marks.jsonl"), content);
        return entry("marks.jsonl", CatalogImport.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private List<CatalogImport.CatalogRecord> scan(CatalogManifest.Entry entry) {
        List<CatalogImport.CatalogRecord> records = new ArrayList<>();
        CatalogImport.scan(entry, raw, normalizer, records::add);
        return records;
    }

    private static String line(String mark, String status, String classes, String sourceId) {
        return "{\"mark\":\"" + mark + "\",\"status\":\"" + status + "\",\"nice_classes\":" + classes
                + ",\"source_id\":\"" + sourceId + "\"}";
    }

    @Test
    void passesTheKeptRecordsInFileOrderAndCountsTheLines() throws IOException {
        CatalogManifest.Entry entry = write(String.join("\n",
                line("Zorvex", "REGISTERED", "[25]", "a1"),
                line("Other", "REGISTERED", "[9]", "a2"),
                "",
                line("Brask", "PENDING", "[35, 25]", "a3"),
                line("Old", "CANCELLED", "[25]", "a4")) + "\n");

        List<CatalogImport.CatalogRecord> kept = scan(entry);

        assertThat(kept).extracting(CatalogImport.CatalogRecord::sourceId).containsExactly("a1", "a3");
        assertThat(CatalogImport.scan(entry, raw, normalizer, r -> { })).isEqualTo(new CatalogImport.ScanResult(4, 2));
    }

    @Test
    void lineEndingsAndAMissingFinalNewlineDoNotMatter() throws IOException {
        String a = line("Zorvex", "REGISTERED", "[25]", "a1");
        String b = line("Brask", "REGISTERED", "[25]", "a2");

        assertThat(scan(write(a + "\r\n" + b + "\r\n"))).hasSize(2);
        assertThat(scan(write(a + "\n" + b))).hasSize(2);
        assertThat(scan(write("\n\n" + a + "\n\n" + b + "\n\n"))).hasSize(2);
        assertThat(scan(write(""))).isEmpty();
    }

    @Test
    void theLineNumberCountsEveryLineIncludingBlankOnes() throws IOException {
        CatalogManifest.Entry entry = write(line("Zorvex", "REGISTERED", "[25]", "a1") + "\n\n\n{broken}\n");

        assertThatThrownBy(() -> scan(entry)).isInstanceOf(CatalogFormatException.class).hasMessageContaining("line 4");
    }

    @Test
    void aBadLineAfterManyGoodOnesStillRejectsTheBatch() throws IOException {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            data.append(line("Mark" + letters(i), "REGISTERED", "[25]", "id" + i)).append('\n');
        }
        data.append(line("SecretMark", "FAMOUS", "[25]", "x")).append('\n');
        CatalogManifest.Entry entry = write(data.toString());

        assertThatThrownBy(() -> scan(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("line 5001").hasMessageNotContaining("SecretMark");
    }

    @Test
    void aFileThatChangesAfterTheCheckIsRefusedAtTheEndOfTheScan() throws IOException {
        CatalogManifest.Entry entry = write(line("Zorvex", "REGISTERED", "[25]", "a1") + "\n");
        CatalogImport.verifyFiles(entry, raw);
        Files.writeString(raw.resolve("marks.jsonl"), line("Brask", "REGISTERED", "[25]", "a1") + "\n");

        assertThatThrownBy(() -> scan(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void aLineWithoutALineBreakThatIsTooLongIsRefused() throws IOException {
        CatalogManifest.Entry entry = write("x".repeat(150_000));

        assertThatThrownBy(() -> scan(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("line 1").hasMessageContaining("longer than");
    }

    // --- a big file, a small heap ---

    /** Distinct letters for a counter: normalization keeps them, so the marks stay distinct. */
    static String letters(int n) {
        StringBuilder text = new StringBuilder();
        int rest = n;
        do {
            text.append((char) ('a' + rest % 26));
            rest /= 26;
        } while (rest > 0);
        return text.toString();
    }

    private static String sha256Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Writes {@link #LINES} lines of about 250 bytes: more than the whole heap of the child JVM. */
    private Path bigFile() throws IOException {
        Path file = raw.resolve("big.jsonl");
        String filler = "lorem ipsum dolor ".repeat(8).trim();
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < LINES; i++) {
                String status = i % 5 == 0 ? "CANCELLED" : "REGISTERED";
                out.write(line("Brand " + letters(i) + " " + filler, status, i % 7 == 0 ? "[9]" : "[25, 35]",
                        "serial-" + i));
                out.write('\n');
            }
        }
        return file;
    }

    @Test
    void aFileLargerThanTheHeapIsScannedWithoutLoadingIt() throws Exception {
        Path file = bigFile();
        assertThat(Files.size(file)).as("the file must not fit in the heap of the child JVM")
                .isGreaterThan(HEAP_MEGABYTES * 1024L * 1024L);

        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx" + HEAP_MEGABYTES + "m", "-cp", classPath,
                CatalogScanMain.class.getName(), raw.toString(), file.getFileName().toString(), sha256Of(file))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        // i % 5 == 0 is cancelled (filtered), and i % 7 == 0 has class 9 only (filtered)
        long expectedKept = IntStream.range(0, LINES).filter(i -> i % 5 != 0 && i % 7 != 0).count();
        assertThat(output.trim()).isEqualTo("read=" + LINES + " kept=" + expectedKept);
    }
}
