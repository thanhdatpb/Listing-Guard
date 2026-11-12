package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The exception file of ADR-022 section 5: what it lets through and what it refuses. */
class BreakingChangeExceptionsTest {

    private static final String ADR_NAME = "ADR-099-synthetic.md";
    private static final Change REMOVED = new Change(Rule.RESPONSE_PROPERTY_REMOVED, "schema Thing.label", "");
    private static final Change ADDED = new Change(Rule.RESPONSE_PROPERTY_ADDED, "schema Thing.color", "");

    @TempDir
    Path directory;

    private Path adrs() throws IOException {
        Path adrs = Files.createDirectories(directory.resolve("adr"));
        Files.writeString(adrs.resolve(ADR_NAME), "# ADR-099\n\nRemoves `schema Thing.label` because ...\n", StandardCharsets.UTF_8);
        return adrs;
    }

    private BreakingChangeExceptions load(String yaml) throws IOException {
        Path file = directory.resolve("breaking-changes.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return BreakingChangeExceptions.load(file);
    }

    private static String file(String baseline, String exceptions) {
        return "format: listing-guard/breaking-changes/v1\nbaseline: " + baseline + "\nexceptions:" + exceptions + "\n";
    }

    private static String entry(String rule, String location, String reason, String adr) {
        return "\n  - rule: " + rule + "\n    location: " + location + "\n    reason: " + reason + "\n    adr: " + adr;
    }

    @Test
    void aDeclaredBreakingChangeWithReasonAndAdrPasses() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.3",
                entry("response-property-removed", "schema Thing.label", "No client reads it.", ADR_NAME)));

        assertThat(exceptions.problems("v1.2.3", List.of(REMOVED, ADDED), adrs())).isEmpty();
        assertThat(exceptions.declaring(REMOVED)).isPresent();
        assertThat(exceptions.declaring(ADDED)).isEmpty();
    }

    @Test
    void anUndeclaredBreakingChangeFails() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.3", " []"));

        assertThat(exceptions.problems("v1.2.3", List.of(REMOVED, ADDED), adrs()))
                .containsExactly("Breaking change not declared: response-property-removed at schema Thing.label");
    }

    @Test
    void additiveAndNonBreakingChangesNeedNoEntry() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.3", " []"));
        Change deprecated = new Change(Rule.OPERATION_DEPRECATED, "GET /things", "");

        assertThat(exceptions.problems("v1.2.3", List.of(ADDED, deprecated), adrs())).isEmpty();
    }

    @Test
    void anEntryThatMatchesNoChangeIsStale() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.3",
                entry("response-property-removed", "schema Thing.label", "No client reads it.", ADR_NAME)));

        assertThat(exceptions.problems("v1.2.3", List.of(ADDED), adrs()))
                .singleElement().asString().contains("matches no breaking change against v1.2.3");
    }

    @Test
    void theBaselineOfTheFileMustBeTheBaselineFile() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.2", " []"));

        assertThat(exceptions.problems("v1.2.3", List.of(), adrs()))
                .singleElement().asString().contains("baseline is v1.2.2 but the baseline file is v1.2.3");
    }

    @Test
    void anEntryNeedsAReasonAndAnAdrThatNamesTheLocation() throws IOException {
        Path adrs = adrs();
        Files.writeString(adrs.resolve("ADR-098-silent.md"), "# ADR-098\n\nNothing about it.\n", StandardCharsets.UTF_8);

        assertThat(load(file("v1.2.3", entry("response-property-removed", "schema Thing.label", "\"\"", ADR_NAME)))
                .problems("v1.2.3", List.of(REMOVED), adrs)).singleElement().asString().contains("reason is empty");
        assertThat(load(file("v1.2.3", entry("response-property-removed", "schema Thing.label", "Why.", "ADR-098-silent.md")))
                .problems("v1.2.3", List.of(REMOVED), adrs)).singleElement().asString().contains("does not mention the location");
        assertThat(load(file("v1.2.3", entry("response-property-removed", "schema Thing.label", "Why.", "ADR-097-missing.md")))
                .problems("v1.2.3", List.of(REMOVED), adrs)).singleElement().asString().contains("does not exist");
        assertThat(load(file("v1.2.3", entry("response-property-removed", "schema Thing.label", "Why.", "notes.txt")))
                .problems("v1.2.3", List.of(REMOVED), adrs)).singleElement().asString().contains("adr must be the file name of an ADR");
    }

    @Test
    void onlyBreakingRulesCanBeDeclared() throws IOException {
        BreakingChangeExceptions exceptions = load(file("v1.2.3",
                entry("response-property-added", "schema Thing.color", "Why.", ADR_NAME)));

        assertThat(exceptions.problems("v1.2.3", List.of(ADDED), adrs()))
                .anySatisfy(problem -> assertThat(problem).contains("is not a breaking rule"));
    }

    @Test
    void anUnknownMemberOrFormatIsRefused() throws IOException {
        assertThatThrownBy(() -> load(file("v1.2.3", " []") + "approved: true\n")).isInstanceOf(UncheckedIOException.class);
        assertThat(load("format: other\nbaseline: v1.2.3\nexceptions: []\n").problems("v1.2.3", List.of(), adrs()))
                .singleElement().asString().contains("format must be listing-guard/breaking-changes/v1");
    }

    @Test
    void theCommittedFileIsReadable() {
        BreakingChangeExceptions committed = BreakingChangeExceptions.load(Path.of("..", "docs", "api", "breaking-changes.yaml"));

        assertThat(committed.format()).isEqualTo(BreakingChangeExceptions.FORMAT);
        assertThat(committed.baseline()).isEqualTo(ContractBaseline.find(Path.of("..", "docs", "api", "baseline")).version());
    }
}
