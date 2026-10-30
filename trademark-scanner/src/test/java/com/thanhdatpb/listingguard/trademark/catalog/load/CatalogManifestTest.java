package com.thanhdatpb.listingguard.trademark.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogManifestTest {

    private static final String SHA = "a".repeat(64);

    private static final String VALID = """
            catalogs:
              - version: synthetic-2026-11
                file: synthetic.jsonl
                format_version: jsonl-1
                source_name: Synthetic marks
                url: https://example.invalid/synthetic
                license: test only
                source_date: 2026-10-30
                sha256: %s
                filters:
                  include_classes: [35, 25]
                  include_status: [PENDING, REGISTERED]
            """.formatted(SHA);

    @TempDir
    Path dir;

    @Test
    void readsAnEntry() {
        CatalogManifest manifest = CatalogManifest.parse(VALID);

        CatalogManifest.Entry entry = manifest.entry("synthetic-2026-11");
        assertThat(entry.formatVersion()).isEqualTo("jsonl-1");
        assertThat(entry.keyed()).as("the one-file form has no source key").isFalse();
        assertThat(entry.sources()).hasSize(1);
        CatalogManifest.Source source = entry.sources().get(0);
        assertThat(source.key()).isNull();
        assertThat(source.file()).isEqualTo("synthetic.jsonl");
        assertThat(source.sourceName()).isEqualTo("Synthetic marks");
        assertThat(source.url()).isEqualTo("https://example.invalid/synthetic");
        assertThat(source.license()).isEqualTo("test only");
        assertThat(source.sourceDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(source.sha256()).isEqualTo(SHA);
        assertThat(entry.includeClasses()).containsExactlyInAnyOrder(25, 35);
        assertThat(entry.includeStatus()).containsExactlyInAnyOrder(MarkStatus.REGISTERED, MarkStatus.PENDING);
        assertThat(entry.filtersJson()).isEqualTo("{\"include_classes\":[25,35],\"include_status\":[\"REGISTERED\",\"PENDING\"]}");
    }

    @Test
    void loadsTheFileFromADirectory() throws IOException {
        Files.writeString(dir.resolve("manifest.yaml"), VALID);

        assertThat(CatalogManifest.load(dir).catalogs()).hasSize(1);
        assertThatThrownBy(() -> CatalogManifest.load(dir.resolve("missing"))).isInstanceOf(CatalogFormatException.class);
    }

    @Test
    void anEmptyListIsAllowed() {
        assertThat(CatalogManifest.parse("catalogs: []\n").catalogs()).isEmpty();
    }

    @Test
    void anUnknownVersionIsReported() {
        assertThatThrownBy(() -> CatalogManifest.parse(VALID).entry("other")).hasMessageContaining("no catalog 'other'");
    }

    private static void assertRejected(String yaml, String message) {
        assertThatThrownBy(() -> CatalogManifest.parse(yaml)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining(message);
    }

    @Test
    void rejectsAMissingLicenseOrSourceDate() {
        assertRejected(VALID.replaceFirst("(?m)^ *license:.*\n", ""), "license");
        assertRejected(VALID.replaceFirst("(?m)^ *source_date:.*\n", ""), "source_date");
        assertRejected(VALID.replace("license: test only", "license: ''"), "license");
    }

    @Test
    void rejectsAnyOtherMissingKey() {
        for (String key : new String[]{"file", "format_version", "source_name", "url", "sha256", "filters"}) {
            assertThatThrownBy(() -> CatalogManifest.parse(VALID.replaceFirst("(?m)^ {4}" + key + ":", "    x_" + key + ":")))
                    .as(key).isInstanceOf(CatalogFormatException.class);
        }
    }

    @Test
    void rejectsAnUnknownKey() {
        assertRejected(VALID.replace("license: test only\n", "license: test only\n    extra: 1\n"), "Unknown key 'extra'");
        assertRejected(VALID + "other: 1\n", "Unknown top-level key");
        assertRejected(VALID.replace("include_status:", "include_state:"), "Unknown filter");
    }

    @Test
    void rejectsABadDateHashFileNameAndLabel() {
        assertRejected(VALID.replace("2026-10-30", "30/10/2026"), "ISO date");
        assertRejected(VALID.replace(SHA, "xyz"), "sha256");
        assertRejected(VALID.replace("file: synthetic.jsonl", "file: ../synthetic.jsonl"), "plain file name");
        assertRejected(VALID.replace("file: synthetic.jsonl", "file: sub/synthetic.jsonl"), "plain file name");
        assertRejected(VALID.replace("file: synthetic.jsonl", "file: https://example.invalid/x.jsonl"), "plain file name");
        assertRejected(VALID.replace("version: synthetic-2026-11", "version: Bad Label"), "version");
    }

    @Test
    void rejectsBadFilters() {
        assertRejected(VALID.replace("[35, 25]", "[]"), "include_classes");
        assertRejected(VALID.replace("[35, 25]", "[0, 25]"), "1 to 45");
        assertRejected(VALID.replace("[35, 25]", "[25, 46]"), "1 to 45");
        assertRejected(VALID.replace("[35, 25]", "['25']"), "1 to 45");
        assertRejected(VALID.replace("[PENDING, REGISTERED]", "[FAMOUS]"), "unknown status");
        assertRejected(VALID.replace("[PENDING, REGISTERED]", "[]"), "include_status");
    }

    // --- the sources: form (ADR-009, PR A2b) ---

    private static final String SOURCES = """
            catalogs:
              - version: pod-2026-11
                format_version: jsonl-1
                sources:
                  - source_key: uspto
                    file: uspto.jsonl
                    source_name: Synthetic office data
                    url: https://example.invalid/office
                    license: test only
                    source_date: 2026-10-30
                    sha256: %s
                  - source_key: internal
                    file: internal.jsonl
                    source_name: Synthetic internal list
                    url: https://example.invalid/internal
                    license: internal use
                    source_date: 2026-09-01
                    sha256: %s
                filters:
                  include_classes: [25, 21]
                  include_status: [REGISTERED]
            """.formatted(SHA, "b".repeat(64));

    @Test
    void readsAnEntryWithSourcesOrderedByKey() {
        CatalogManifest.Entry entry = CatalogManifest.parse(SOURCES).entry("pod-2026-11");

        assertThat(entry.keyed()).isTrue();
        assertThat(entry.formatVersion()).isEqualTo("jsonl-1");
        assertThat(entry.sources()).extracting(CatalogManifest.Source::key).containsExactly("internal", "uspto");
        CatalogManifest.Source internal = entry.sources().get(0);
        assertThat(internal.file()).isEqualTo("internal.jsonl");
        assertThat(internal.sourceName()).isEqualTo("Synthetic internal list");
        assertThat(internal.url()).isEqualTo("https://example.invalid/internal");
        assertThat(internal.license()).isEqualTo("internal use");
        assertThat(internal.sourceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(internal.sha256()).isEqualTo("b".repeat(64));
        assertThat(entry.includeClasses()).containsExactlyInAnyOrder(21, 25);
        assertThat(entry.filtersJson()).isEqualTo("{\"include_classes\":[21,25],\"include_status\":[\"REGISTERED\"]}");
    }

    @Test
    void theOrderOfTheSourcesInTheFileDoesNotMatter() {
        String swapped = SOURCES.replace("source_key: uspto", "source_key: aaa-first");

        assertThat(CatalogManifest.parse(swapped).entry("pod-2026-11").sources())
                .extracting(CatalogManifest.Source::key).containsExactly("aaa-first", "internal");
    }

    @Test
    void theOneFileFormAndTheSourcesFormCanShareAManifest() {
        String both = SOURCES + VALID.substring(VALID.indexOf("  - version"));

        CatalogManifest manifest = CatalogManifest.parse(both);

        assertThat(manifest.catalogs()).extracting(CatalogManifest.Entry::version)
                .containsExactly("pod-2026-11", "synthetic-2026-11");
    }

    @Test
    void rejectsMixingTheTwoForms() {
        assertRejected(SOURCES.replace("    sources:\n", "    file: x.jsonl\n    sources:\n"), "belongs inside each source");
        assertRejected(SOURCES.replace("    sources:\n", "    sha256: " + SHA + "\n    sources:\n"),
                "belongs inside each source");
    }

    @Test
    void rejectsABadSourcesList() {
        assertRejected(SOURCES.replaceFirst("(?s)sources:.*(?=\\s{4}filters:)", "sources: []\n"), "non-empty list");
        assertRejected(SOURCES.replaceFirst("(?s)sources:.*(?=\\s{4}filters:)", "sources: nope\n"), "non-empty list");
        assertRejected(SOURCES.replaceFirst("(?s)sources:.*(?=\\s{4}filters:)", "sources:\n      - plain\n"),
                "must be a mapping");
    }

    @Test
    void rejectsASourceWithAMissingOrUnknownKey() {
        for (String key : new String[]{"source_key", "file", "source_name", "url", "license", "source_date", "sha256"}) {
            assertThatThrownBy(() -> CatalogManifest.parse(SOURCES.replaceFirst("(?m)^( +(- )?)" + key + ":", "$1x_" + key + ":")))
                    .as(key).isInstanceOf(CatalogFormatException.class);
        }
        assertRejected(SOURCES.replaceFirst("(?m)^ {8}license:.*\n", ""), "license");
        assertRejected(SOURCES.replace("license: internal use\n", "license: internal use\n        extra: 1\n"),
                "Unknown key 'extra'");
    }

    @Test
    void rejectsABadOrRepeatedSourceKeyOrFile() {
        assertRejected(SOURCES.replace("source_key: uspto", "source_key: Uspto"), "source_key");
        assertRejected(SOURCES.replace("source_key: uspto", "source_key: 'a:b'"), "source_key");
        assertRejected(SOURCES.replace("source_key: uspto", "source_key: internal"), "source_key 'internal' is listed twice");
        assertRejected(SOURCES.replace("file: uspto.jsonl", "file: internal.jsonl"), "file 'internal.jsonl' is listed twice");
        assertRejected(SOURCES.replace("file: uspto.jsonl", "file: ../uspto.jsonl"), "plain file name");
        assertRejected(SOURCES.replace(SHA, "xyz"), "sha256");
        assertRejected(SOURCES.replace("2026-09-01", "01/09/2026"), "ISO date");
    }

    @Test
    void theFiltersOfASourcesEntryAreStillRequired() {
        assertRejected(SOURCES.replace("include_classes: [25, 21]", "include_classes: []"), "include_classes");
        assertRejected(SOURCES.replaceFirst("(?s)\\s{4}filters:.*", "\n"), "filters");
    }

    @Test
    void rejectsADuplicateAndANonMapping() {
        String second = VALID.substring(VALID.indexOf("  - version"));
        assertRejected(VALID + second, "listed twice");
        assertRejected("- a\n", "mapping");
        assertRejected("catalogs: nope\n", "list");
    }
}
