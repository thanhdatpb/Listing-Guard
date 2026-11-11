package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoCatalogConverter;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConversionException;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoConvertFilters;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                <case-file><serial-number>97000001</serial-number><transaction-date>20241101</transaction-date>
                  <case-file-header><status-code>700</status-code><mark-identification>Zorvex</mark-identification>
                    <mark-drawing-code>4000</mark-drawing-code></case-file-header>
                  <classifications><classification><international-code>025</international-code></classification>
                  </classifications></case-file>
              </action-keys></file-segments></application-information>
            </trademark-applications-daily>
            """;

    private static final String JSONL =
            "{\"mark\":\"Zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000001\"}\n";

    private static final UsptoStatusMap MAP = UsptoStatusMap.loadClasspath("uspto/uspto-status-map-2026-11.yaml");
    private static final UsptoConvertFilters FILTERS = UsptoConvertFilters.loadClasspath(
            "uspto/uspto-convert-filters-2026-11.yaml");
    private static final TextNormalizer NORMALIZER = new TextNormalizer(
            TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml").normalization());

    @TempDir
    Path raw;

    private UsptoConvertJob job;

    private static UsptoConvertJob job(boolean allowOtherVersion, boolean strict) {
        return new UsptoConvertJob(MAP, FILTERS, NORMALIZER,
                new UsptoCatalogConverter.Options(allowOtherVersion, strict, 1_000_000));
    }

    @BeforeEach
    void setUp() throws IOException {
        job = job(false, false);
        Files.createDirectories(raw.resolve("uspto"));
        Files.writeString(raw.resolve("uspto/daily-test.xml"), XML, StandardCharsets.UTF_8);
    }

    private UsptoConvertJob.Result run(String input, String output, boolean overwrite) throws IOException {
        return job.run(raw, List.of(input), null, output, overwrite);
    }

    @Test
    void writesTheJsonlAndTheReportNextToEachOtherInTheRawDirectory() throws IOException {
        UsptoConvertJob.Result result = run("uspto/daily-test.xml", null, false);

        assertThat(result.output()).isEqualTo(raw.toAbsolutePath().normalize().resolve("daily-test.jsonl"));
        assertThat(Files.readString(result.output())).isEqualTo(JSONL);
        JsonNode report = new ObjectMapper().readTree(Files.readString(result.reportFile()));
        assertThat(result.reportFile().getFileName().toString()).isEqualTo("daily-test.jsonl.report.json");
        assertThat(report.get("format").asText()).isEqualTo("listing-guard/uspto-convert-report/v2");
        assertThat(report.get("recordsWritten").asInt()).isEqualTo(1);
        assertThat(report.get("outputSha256").asText()).isEqualTo(result.report().outputSha256()).hasSize(64);
        assertThat(report.get("files").get(0).get("name").asText()).isEqualTo("uspto/daily-test.xml");
        assertThat(report.get("files").get(0).get("versionNo").asText()).isEqualTo("2.0");
        assertThat(report.get("filtersVersion").asText()).isEqualTo(FILTERS.version());
        try (Stream<Path> files = Files.list(raw)) {
            assertThat(files.map(p -> p.getFileName().toString())).as("no .part file is left")
                    .containsExactlyInAnyOrder("uspto", "daily-test.jsonl", "daily-test.jsonl.report.json");
        }
    }

    @Test
    void takesTheOutputNameFromTheCaller() throws IOException {
        UsptoConvertJob.Result result = run("uspto/daily-test.xml", "uspto-2026-11.jsonl", false);

        assertThat(result.output().getFileName().toString()).isEqualTo("uspto-2026-11.jsonl");
    }

    @Test
    void refusesAnExistingOutputUnlessAskedToReplaceIt() throws IOException {
        run("uspto/daily-test.xml", null, false);

        assertThatThrownBy(() -> run("uspto/daily-test.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--overwrite");
        assertThat(run("uspto/daily-test.xml", null, true).report().recordsWritten()).isEqualTo(1);
    }

    @Test
    void readsAndWritesOnlyInsideTheRawDirectory() {
        assertThatThrownBy(() -> run("../outside.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside");
        assertThatThrownBy(() -> run("uspto/daily-test.xml", "../escape.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> run("uspto/daily-test.xml", "sub/dir.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> run("uspto/daily-test.xml", "x.jsonl.part", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain file name");
        assertThatThrownBy(() -> run("uspto", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a file");
        assertThatThrownBy(() -> run("uspto/missing.xml", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a file");
        assertThatThrownBy(() -> job.run(raw, null, "../outside", "x.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside");
        assertThatThrownBy(() -> job.run(raw, null, "uspto/daily-test.xml", "x.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a folder");
    }

    @Test
    void theInputCannotBeTheOutput() throws IOException {
        Files.writeString(raw.resolve("same.jsonl"), XML);

        assertThatThrownBy(() -> run("same.jsonl", "same.jsonl", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be one of the input");
    }

    @Test
    void asksForAnInputAndNotForBothKinds() {
        assertThatThrownBy(() -> job.run(raw, null, null, null, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--input").hasMessageContaining("--input-dir");
        assertThatThrownBy(() -> job.run(raw, List.of(), "  ", null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(raw, List.of(" "), null, "x.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(raw, List.of("uspto/daily-test.xml"), "uspto", "x.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
    }

    // --- several files ---

    private void twoDays() throws IOException {
        Files.writeString(raw.resolve("uspto/apc241101.xml"), XML, StandardCharsets.UTF_8);
        // the next day the same serial number is cancelled
        Files.writeString(raw.resolve("uspto/apc241102.xml"),
                XML.replace("20241101", "20241102").replace("<status-code>700<", "<status-code>710<"),
                StandardCharsets.UTF_8);
    }

    @Test
    void severalInputsGoIntoOneCatalogWithTheLatestRecordOfEachSerialNumber() throws IOException {
        twoDays();

        UsptoConvertJob.Result both = job.run(raw, List.of("uspto/apc241102.xml", "uspto/apc241101.xml"), null,
                "both.jsonl", false);
        UsptoConvertJob.Result first = job.run(raw, List.of("uspto/apc241101.xml"), null, "first.jsonl", false);

        assertThat(Files.readString(both.output())).as("cancelled on the second day").isEmpty();
        assertThat(both.report().deadRemoved()).isEqualTo(1);
        assertThat(both.report().superseded()).isEqualTo(1);
        assertThat(both.report().files()).extracting(f -> f.name()).containsExactly("uspto/apc241101.xml",
                "uspto/apc241102.xml");
        assertThat(Files.readString(first.output())).isEqualTo(JSONL);
    }

    @Test
    void aFolderGivesEveryXmlFileInIt() throws IOException {
        twoDays();
        Files.writeString(raw.resolve("uspto/readme.txt"), "not an xml file");
        Files.createDirectories(raw.resolve("uspto/sub"));
        Files.writeString(raw.resolve("uspto/sub/apc241103.xml"), XML, StandardCharsets.UTF_8);

        UsptoConvertJob.Result result = job.run(raw, null, "uspto", "folder.jsonl", false);

        // the file with no date in its name sorts first; the sub-folder and the .txt file are not read
        assertThat(result.report().files()).extracting(f -> f.name())
                .containsExactly("uspto/daily-test.xml", "uspto/apc241101.xml", "uspto/apc241102.xml");
    }

    @Test
    void severalFilesNeedAnOutputName() throws IOException {
        twoDays();

        assertThatThrownBy(() -> job.run(raw, List.of("uspto/apc241101.xml", "uspto/apc241102.xml"), null, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--output is required");
        assertThatThrownBy(() -> job.run(raw, null, "uspto", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--output is required");
        assertThatThrownBy(() -> job.run(raw, null, "uspto/sub", "x.jsonl", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileGivenTwiceIsRefused() {
        assertThatThrownBy(() -> job.run(raw, List.of("uspto/daily-test.xml", "uspto/../uspto/daily-test.xml"), null,
                "x.jsonl", false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    // --- problems ---

    @Test
    void aFileThatCannotBeConvertedLeavesNoOutputAndKeepsAnOlderOne() throws IOException {
        Files.writeString(raw.resolve("uspto/bad.xml"), XML.replace("<version-no>2.0<", "<version-no>1.0<"));
        Files.writeString(raw.resolve("bad.jsonl"), "older\n");

        assertThatThrownBy(() -> run("uspto/bad.xml", null, true))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("version-no '1.0'");

        assertThat(Files.readString(raw.resolve("bad.jsonl"))).isEqualTo("older\n");
        assertThat(raw.resolve("bad.jsonl.part")).doesNotExist();
        assertThat(raw.resolve("bad.jsonl.report.json")).doesNotExist();
    }

    @Test
    void aBrokenFileAmongGoodOnesIsReportedAndTheRunIsIncomplete() throws IOException {
        Files.writeString(raw.resolve("uspto/apc241101.xml"), XML, StandardCharsets.UTF_8);
        Files.writeString(raw.resolve("uspto/apc241102.xml"), XML.replace("</case-file>", ""), StandardCharsets.UTF_8);

        UsptoConvertJob.Result result = job.run(raw, null, "uspto", "partial.jsonl", false);

        assertThat(result.report().completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(Files.readString(result.output())).isEqualTo(JSONL);
        JsonNode report = new ObjectMapper().readTree(Files.readString(result.reportFile()));
        assertThat(report.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(report.get("filesFailed").asInt()).isEqualTo(1);
        // daily-test.xml (no date in its name) is read first, then the two days
        assertThat(report.get("files").get(2).get("name").asText()).isEqualTo("uspto/apc241102.xml");
        assertThat(report.get("files").get(2).get("status").asText()).isEqualTo("FAILED");
        assertThat(report.get("files").get(2).get("error").asText()).startsWith("uspto/apc241102.xml: ");
    }

    @Test
    void strictStopsAtTheBrokenFileAndWritesNothing() throws IOException {
        Files.writeString(raw.resolve("uspto/apc241101.xml"), XML, StandardCharsets.UTF_8);
        Files.writeString(raw.resolve("uspto/apc241102.xml"), XML.replace("</case-file>", ""), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> job(false, true).run(raw, null, "uspto", "strict.jsonl", false))
                .isInstanceOf(UsptoConversionException.class).hasMessageStartingWith("uspto/apc241102.xml: ");

        assertThat(raw.resolve("strict.jsonl")).doesNotExist();
        assertThat(raw.resolve("strict.jsonl.part")).doesNotExist();
        assertThat(raw.resolve("strict.jsonl.report.json")).doesNotExist();
    }

    @Test
    void theOtherVersionFlagIsPassedToTheConverter() throws IOException {
        Files.writeString(raw.resolve("uspto/v21.xml"), XML.replace("<version-no>2.0<", "<version-no>2.1<"));

        UsptoConvertJob.Result result = job(true, false).run(raw, List.of("uspto/v21.xml"), null, null, false);

        assertThat(result.report().otherVersionAllowed()).isTrue();
    }
}
