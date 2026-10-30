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
        assertThat(entry.file()).isEqualTo("synthetic.jsonl");
        assertThat(entry.formatVersion()).isEqualTo("jsonl-1");
        assertThat(entry.sourceName()).isEqualTo("Synthetic marks");
        assertThat(entry.url()).isEqualTo("https://example.invalid/synthetic");
        assertThat(entry.license()).isEqualTo("test only");
        assertThat(entry.sourceDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(entry.sha256()).isEqualTo(SHA);
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

    @Test
    void rejectsADuplicateAndANonMapping() {
        String second = VALID.substring(VALID.indexOf("  - version"));
        assertRejected(VALID + second, "listed twice");
        assertRejected("- a\n", "mapping");
        assertRejected("catalogs: nope\n", "list");
    }
}
