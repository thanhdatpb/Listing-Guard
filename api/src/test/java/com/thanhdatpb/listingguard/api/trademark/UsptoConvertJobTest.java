package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConversionException;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file rules of the {@code uspto-convert} job. Synthetic XML that follows the DTD V2.0; no database. */
class UsptoConvertJobTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <trademark-applications-daily>
              <version><version-no>2.0</version-no><version-date>20041108</version-date></version>
              <application-information><file-segments><file-segment>TRMK</file-segment><action-keys>
                <action-key>NA</action-key>
                <case-file><serial-number>97000001</serial-number>
                  <case-file-header><status-code>700</status-code><mark-identification>Zorvex</mark-identification>
                    <mark-drawing-code>4000</mark-drawing-code></case-file-header>
                  <classifications><classification><international-code>025</international-code></classification>
                  </classifications></case-file>
              </action-keys></file-segments></application-information>
            </trademark-applications-daily>
            """;

    @TempDir
    Path raw;

    private UsptoConvertJob job;

    @BeforeEach
    void setUp() throws IOException {
        UsptoStatusMap map = UsptoStatusMap.loadClasspath("uspto/uspto-status-map-2026-11.yaml");
        TextNormalizer normalizer = new TextNormalizer(
                TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml").normalization());
        job = new UsptoConvertJob(map, normalizer, false);
        Files.createDirectories(raw.resolve("uspto"));
        Files.writeString(raw.resolve("uspto/daily-test.xml"), XML, StandardCharsets.UTF_8);
    }

    @Test
    void writesTheJsonlAndTheReportNextToEachOtherInTheRawDirectory() throws IOException {
        UsptoConvertJob.Result result = job.run(raw, "uspto/daily-test.xml", null, false);

        assertThat(result.output()).isEqualTo(raw.toAbsolutePath().normalize().resolve("daily-test.jsonl"));
        assertThat(Files.readString(result.output())).isEqualTo(
                "{\"mark\":\"Zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000001\"}\n");
        JsonNode report = new ObjectMapper().readTree(Files.readString(result.reportFile()));
        assertThat(result.reportFile().getFileName().toString()).isEqualTo("daily-test.jsonl.report.json");
        assertThat(report.get("recordsWritten").asInt()).isEqualTo(1);
        assertThat(report.get("outputSha256").asText()).isEqualTo(result.report().outputSha256()).hasSize(64);
        assertThat(report.get("versionNo").asText()).isEqualTo("2.0");
        try (Stream<Path> files = Files.list(raw)) {
            assertThat(files.map(p -> p.getFileName().toString())).as("no .part file is left")
                    .containsExactlyInAnyOrder("uspto", "daily-test.jsonl", "daily-test.jsonl.report.json");
        }
    }

    @Test
    void takesTheOutputNameFromTheCaller() throws IOException {
        UsptoConvertJob.Result result = job.run(raw, "uspto/daily-test.xml", "uspto-2026-11.jsonl", false);

        assertThat(result.output().getFileName().toString()).isEqualTo("uspto-2026-11.jsonl");
    }

    @Test
    void refusesAnExistingOutputUnlessAskedToReplaceIt() throws IOException {
        job.run(raw, "uspto/daily-test.xml", null, false);

        assertThatThrownBy(() -> job.run(raw, "uspto/daily-test.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--overwrite");
        assertThat(job.run(raw, "uspto/daily-test.xml", null, true).report().recordsWritten()).isEqualTo(1);
    }

    @Test
    void readsAndWritesOnlyInsideTheRawDirectory() {
        assertThatThrownBy(() -> job.run(raw, "../outside.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside");
        assertThatThrownBy(() -> job.run(raw, "uspto/daily-test.xml", "../escape.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> job.run(raw, "uspto/daily-test.xml", "sub/dir.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> job.run(raw, "uspto/daily-test.xml", "x.jsonl.part", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> job.run(raw, "uspto", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a file");
        assertThatThrownBy(() -> job.run(raw, "uspto/missing.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a file");
    }

    @Test
    void theInputCannotBeTheOutput() throws IOException {
        Files.writeString(raw.resolve("same.jsonl"), XML);

        assertThatThrownBy(() -> job.run(raw, "same.jsonl", "same.jsonl", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be the input");
    }

    @Test
    void asksForAnInput() {
        assertThatThrownBy(() -> job.run(raw, null, null, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--input");
        assertThatThrownBy(() -> job.run(raw, "  ", null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileThatCannotBeConvertedLeavesNoOutputAndKeepsAnOlderOne() throws IOException {
        Files.writeString(raw.resolve("uspto/bad.xml"), XML.replace("<version-no>2.0<", "<version-no>1.0<"));
        Files.writeString(raw.resolve("bad.jsonl"), "older\n");

        assertThatThrownBy(() -> job.run(raw, "uspto/bad.xml", null, true))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("version-no '1.0'");

        assertThat(Files.readString(raw.resolve("bad.jsonl"))).isEqualTo("older\n");
        assertThat(raw.resolve("bad.jsonl.part")).doesNotExist();
        assertThat(raw.resolve("bad.jsonl.report.json")).doesNotExist();
    }

    @Test
    void theOtherVersionFlagIsPassedToTheConverter() throws IOException {
        Files.writeString(raw.resolve("uspto/v21.xml"), XML.replace("<version-no>2.0<", "<version-no>2.1<"));
        UsptoStatusMap map = UsptoStatusMap.loadClasspath("uspto/uspto-status-map-2026-11.yaml");
        TextNormalizer normalizer = new TextNormalizer(
                TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml").normalization());

        UsptoConvertJob.Result result = new UsptoConvertJob(map, normalizer, true).run(raw, "uspto/v21.xml", null, false);

        assertThat(result.report().otherVersionAllowed()).isTrue();
    }
}
