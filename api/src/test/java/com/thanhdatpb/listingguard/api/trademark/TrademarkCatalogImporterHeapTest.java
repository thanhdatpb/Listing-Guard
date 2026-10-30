package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A catalog file larger than the heap is imported (ADR-009, PR A2b): the import runs in a child JVM with a heap smaller
 * than the file, against the database of this test. Synthetic marks only.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TrademarkCatalogImporterHeapTest {

    private static final int LINES = 200_000;
    /** The lines repeat the marks, so some of the kept lines merge into another one. */
    private static final int DISTINCT_MARKS = 150_000;
    private static final int HEAP_MEGABYTES = 40;

    @Autowired
    PostgreSQLContainer<?> postgres;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    private static String letters(int n) {
        StringBuilder text = new StringBuilder();
        int rest = n;
        do {
            text.append((char) ('a' + rest % 26));
            rest /= 26;
        } while (rest > 0);
        return text.toString();
    }

    /** About 300 bytes a line: the marks are short and each line ends with blanks, which JSON allows. */
    private static String bigData() {
        StringBuilder data = new StringBuilder(LINES * 310);
        String blanks = " ".repeat(200);
        for (int i = 0; i < LINES; i++) {
            String status = i % 5 == 0 ? "CANCELLED" : "REGISTERED";
            String classes = i % 7 == 0 ? "[9]" : "[25, 35]";
            data.append("{\"mark\":\"Brand ").append(letters(i % DISTINCT_MARKS)).append("\",\"status\":\"")
                    .append(status).append("\",\"nice_classes\":").append(classes).append(",\"source_id\":\"serial-")
                    .append(i).append("\"}").append(blanks).append('\n');
        }
        return data.toString();
    }

    @Test
    void aFileLargerThanTheHeapIsImportedWithoutLoadingIt() throws IOException, InterruptedException {
        String label = "big-" + UUID.randomUUID().toString().substring(0, 8);
        // i % 5 == 0 is cancelled and i % 7 == 0 is class 9 only: the filters (class 25, REGISTERED) drop both
        TrademarkFixtures.catalogsDir(dir, label, bigData(), "[25]", "[REGISTERED]");
        assertThat(Files.size(dir.resolve("raw").resolve("synthetic.jsonl")))
                .as("the file must not fit in the heap of the child JVM").isGreaterThan(HEAP_MEGABYTES * 1024L * 1024L);

        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx" + HEAP_MEGABYTES + "m", "-cp", classPath,
                TrademarkImportMain.class.getName(), postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), dir.toString(), label).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        boolean[] seen = new boolean[DISTINCT_MARKS];
        int kept = 0;
        int loaded = 0;
        for (int i = 0; i < LINES; i++) {
            if (i % 5 != 0 && i % 7 != 0) {
                kept++;
                if (!seen[i % DISTINCT_MARKS]) {
                    seen[i % DISTINCT_MARKS] = true;
                    loaded++;
                }
            }
        }
        String result = output.lines().filter(line -> line.startsWith("RESULT ")).findFirst().orElseThrow();
        String[] parts = result.split(" ");
        assertThat(parts[1]).as(result).isEqualTo("IMPORTED");
        assertThat(List.of(parts).subList(3, 7)).as(result).containsExactly(String.valueOf(LINES),
                String.valueOf(LINES - kept), String.valueOf(kept - loaded), String.valueOf(loaded));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_entry e JOIN trademark_catalog_version v "
                + "ON v.id = e.catalog_version_id WHERE v.label = ?", Integer.class, label)).isEqualTo(loaded);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_catalog_source s JOIN trademark_catalog_version v "
                + "ON v.id = s.catalog_version_id WHERE v.label = ?", Integer.class, label)).isEqualTo(1);

        // leave the shared test database as it was
        jdbc.update("DELETE FROM trademark_entry WHERE catalog_version_id IN "
                + "(SELECT id FROM trademark_catalog_version WHERE label = ?)", label);
        jdbc.update("DELETE FROM trademark_catalog_version WHERE label = ?", label);
    }
}
