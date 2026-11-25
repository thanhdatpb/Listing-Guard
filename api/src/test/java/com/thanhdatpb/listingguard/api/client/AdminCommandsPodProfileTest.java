package com.thanhdatpb.listingguard.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/** {@code pod-profile import} through the admin command parser (ADR-048). It needs no database. Data is invented. */
class AdminCommandsPodProfileTest {

    private static final String CSV = """
            product_type,variant,print_area_width_mm,print_area_height_mm,max_chars,source_ref,verified_by,verified_at
            Sample Mug,,90,95,12,MUG-01,Operator A,2026-11-20
            """;

    @TempDir Path dir;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final AdminCommands commands = new AdminCommands(null, new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String run(String... args) {
        captured.reset();
        commands.execute(new DefaultApplicationArguments(args));
        return captured.toString(StandardCharsets.UTF_8);
    }

    private Path file() throws Exception {
        return Files.writeString(dir.resolve("spec.csv"), CSV, StandardCharsets.UTF_8);
    }

    @Test
    void importWritesTheVersionPrintsItsHashAndKeepsTheFileSetSmall() throws Exception {
        Path out = dir.resolve("out");

        String output = run("pod-profile", "import", file().toString(), "--out=" + out);

        assertThat(output).contains("Wrote ").contains("sample-mug-v1.json").contains("1 verified, 0 unverified")
                .containsPattern("SHA-256 [0-9a-f]{64}").contains("Pin the SHA-256");
        assertThat(out.resolve("sample-mug-v1.json")).exists();
        assertThat(run("pod-profile", "import", file().toString(), "--out=" + out))
                .contains("already holds exactly this data").doesNotContain("Wrote");
        try (var files = Files.list(out)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void aDryRunWritesNothingAndExplicitVersionsAreImmutable() throws Exception {
        Path out = dir.resolve("out");

        assertThat(run("pod-profile", "import", file().toString(), "--out=" + out, "--dry-run")).contains("Would write");
        assertThat(out).doesNotExist();

        run("pod-profile", "import", file().toString(), "--out=" + out);
        assertThatIllegalArgumentException().isThrownBy(() ->
                        run("pod-profile", "import", file().toString(), "--out=" + out, "--version=1"))
                .withMessageContaining("sample-mug-v1 already exists").withMessageContaining("Usage:");
    }

    @Test
    void aRefusedFileAndBadArgumentsSayWhyAndShowTheUsage() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> run("pod-profile", "import"))
                .withMessageContaining("Missing the CSV file").withMessageContaining("pod-profile import <file.csv>");
        assertThatIllegalArgumentException().isThrownBy(() -> run("pod-profile", "import", dir.resolve("none.csv").toString()))
                .withMessageContaining("does not exist");
        assertThatIllegalArgumentException().isThrownBy(() -> run("pod-profile", "export"))
                .withMessageContaining("Unknown command");
        assertThatIllegalArgumentException().isThrownBy(() ->
                        run("pod-profile", "import", file().toString(), "--out=" + dir.resolve("o"), "--version=two"))
                .withMessageContaining("not a whole number");
        assertThatIllegalArgumentException().isThrownBy(() ->
                        run("pod-profile", "import", file().toString(), "--out=" + dir.resolve("o"), "--marketplaces=EBAY"))
                .withMessageContaining("Unknown marketplace 'EBAY'");
        Path broken = Files.writeString(dir.resolve("broken.csv"), "product_type,variant\nSample Mug,\n", StandardCharsets.UTF_8);
        assertThatIllegalArgumentException().isThrownBy(() ->
                        run("pod-profile", "import", broken.toString(), "--out=" + dir.resolve("o")))
                .withMessageContaining("Missing columns: print_area_width_mm");
        assertThat(dir.resolve("o")).doesNotExist();
    }
}
