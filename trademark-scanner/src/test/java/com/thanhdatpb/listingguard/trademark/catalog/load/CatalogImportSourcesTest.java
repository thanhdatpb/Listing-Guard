package com.thanhdatpb.listingguard.trademark.catalog.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An entry with {@code sources:} (ADR-009, PR A2b). Synthetic marks only. */
class CatalogImportSourcesTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TextNormalizer normalizer = new TextNormalizer(ruleset.normalization());
    private final NormalizationIdentity identity = NormalizationIdentity.of(ruleset);

    @TempDir
    Path raw;

    private static String line(String mark, String status, String classes, String sourceId, boolean common) {
        return "{\"mark\":\"" + mark + "\",\"status\":\"" + status + "\",\"nice_classes\":" + classes
                + ",\"source_id\":\"" + sourceId + "\"" + (common ? ",\"common_word\":true" : "") + "}";
    }

    private static String line(String mark, String status, String classes, String sourceId) {
        return line(mark, status, classes, sourceId, false);
    }

    private static final String FIRST = String.join("\n",
            line("Zorvex", "REGISTERED", "[25]", "1001"),
            line("Brask", "PENDING", "[25]", "1002"),
            line("Skipped Class", "REGISTERED", "[9]", "1003")) + "\n";

    private static final String SECOND = String.join("\n",
            line("ZORVEX", "REGISTERED", "[35]", "7", true),
            line("Quillmark", "REGISTERED", "[25]", "8"),
            line("Old Brand", "CANCELLED", "[25]", "9")) + "\n";

    private CatalogManifest.Source source(String key, String file, String content, String license, LocalDate date)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(raw.resolve(file), bytes);
        return new CatalogManifest.Source(key, file, "Source " + key, "https://example.invalid/" + key, license, date,
                CatalogImport.sha256(bytes));
    }

    private CatalogManifest.Source first() throws IOException {
        return source("uspto", "first.jsonl", FIRST, "terms of first", LocalDate.of(2026, 10, 30));
    }

    private CatalogManifest.Source second() throws IOException {
        return source("internal", "second.jsonl", SECOND, "terms of second", LocalDate.of(2026, 9, 1));
    }

    private static CatalogManifest.Entry entry(CatalogManifest.Source... sources) {
        return new CatalogManifest.Entry("pod-1", CatalogImport.FORMAT_VERSION, List.of(sources), Set.of(25, 35),
                Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING));
    }

    private PreparedCatalog prepare(CatalogManifest.Entry entry) {
        return CatalogImport.prepare(entry, raw, normalizer, identity);
    }

    @Test
    void mergesTheSameMarkAcrossSources() throws IOException {
        PreparedCatalog catalog = prepare(entry(first(), second()));

        assertThat(catalog.recordsRead()).isEqualTo(6);
        // the class 9 record of the first file and the cancelled record of the second
        assertThat(catalog.recordsFiltered()).isEqualTo(2);
        assertThat(catalog.recordsMerged()).isEqualTo(1);
        assertThat(catalog.recordsLoaded()).isEqualTo(3);
        assertThat(catalog.entries()).extracting(e -> e.mark().normalized()).containsExactly("brask", "quillmark",
                "zorvex");
        CatalogMark zorvex = catalog.entries().get(2).mark();
        assertThat(zorvex.niceClasses()).containsExactly(25, 35);
        assertThat(zorvex.commonWord()).as("one source marks it as a common word").isTrue();
        assertThat(zorvex.sourceIds()).containsExactly("internal:7", "uspto:1001");
        // "ZORVEX" < "Zorvex" by String.compareTo
        assertThat(zorvex.display()).isEqualTo("ZORVEX");
        assertThat(catalog.entries().get(0).mark().sourceIds()).containsExactly("uspto:1002");
    }

    @Test
    void theIdsOfAKeyedSourceAreKeptApartEvenWhenTheFilesUseTheSameId() throws IOException {
        CatalogManifest.Source a = source("one", "a.jsonl", line("Zorvex", "REGISTERED", "[25]", "5") + "\n",
                "t", LocalDate.of(2026, 10, 30));
        CatalogManifest.Source b = source("two", "b.jsonl", line("Zorvex", "REGISTERED", "[25]", "5") + "\n",
                "t", LocalDate.of(2026, 10, 30));

        assertThat(prepare(entry(a, b)).entries().get(0).mark().sourceIds()).containsExactly("one:5", "two:5");
    }

    @Test
    void theStoredSummaryNamesEverySourceAndTheOldestDate() throws IOException {
        PreparedCatalog catalog = prepare(entry(first(), second()));

        assertThat(catalog.sourceName()).isEqualTo("internal: Source internal | uspto: Source uspto");
        assertThat(catalog.sourceReference())
                .isEqualTo("internal: https://example.invalid/internal | uspto: https://example.invalid/uspto");
        assertThat(catalog.sourceLicense()).isEqualTo("internal: terms of second | uspto: terms of first");
        assertThat(catalog.sourceDate()).as("no newer than the oldest source").isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(catalog.fileSha256()).matches("[0-9a-f]{64}").isNotEqualTo(first().sha256())
                .isNotEqualTo(second().sha256());
    }

    @Test
    void theOrderOfTheSourcesChangesNothing() throws IOException {
        CatalogManifest.Source one = first();
        CatalogManifest.Source two = second();

        PreparedCatalog forward = prepare(entry(one, two));
        PreparedCatalog backward = prepare(entry(two, one));

        assertThat(backward).isEqualTo(forward);
        assertThat(forward.version()).matches("pod-1-[0-9a-f]{12}");
    }

    @Test
    void changingOneSourceIsAnotherVersion() throws IOException {
        PreparedCatalog base = prepare(entry(first(), second()));

        // other content of one file, hence another hash
        CatalogManifest.Source changed = source("internal", "second.jsonl",
                SECOND + line("Extra", "REGISTERED", "[25]", "10") + "\n", "terms of second", LocalDate.of(2026, 9, 1));
        assertThat(prepare(entry(first(), changed)).version()).isNotEqualTo(base.version());

        // same content, other terms or date
        CatalogManifest.Source relicensed = source("internal", "second.jsonl", SECOND, "other terms",
                LocalDate.of(2026, 9, 1));
        assertThat(prepare(entry(first(), relicensed)).version()).isNotEqualTo(base.version());
        CatalogManifest.Source redated = source("internal", "second.jsonl", SECOND, "terms of second",
                LocalDate.of(2026, 9, 2));
        assertThat(prepare(entry(first(), redated)).version()).isNotEqualTo(base.version());

        // the key is part of the identity: the stored ids change with it
        CatalogManifest.Source renamed = source("other", "second.jsonl", SECOND, "terms of second",
                LocalDate.of(2026, 9, 1));
        assertThat(prepare(entry(first(), renamed)).version()).isNotEqualTo(base.version());

        // a source more or less
        assertThat(prepare(entry(first())).version()).isNotEqualTo(base.version());

        // and the restored manifest finds the first version again
        assertThat(prepare(entry(first(), second())).version()).isEqualTo(base.version());
    }

    @Test
    void theFiltersApplyToTheWholeCatalogAndChangeTheVersion() throws IOException {
        CatalogManifest.Entry both = entry(first(), second());
        CatalogManifest.Entry onlyTwentyFive = new CatalogManifest.Entry("pod-1", CatalogImport.FORMAT_VERSION,
                both.sources(), Set.of(25), both.includeStatus());

        PreparedCatalog catalog = prepare(onlyTwentyFive);

        assertThat(catalog.entries()).extracting(e -> e.mark().normalized()).containsExactly("brask", "quillmark",
                "zorvex");
        // the class 35 record of the second file is dropped by the filter, so its class is not united
        assertThat(catalog.entries().get(2).mark().niceClasses()).containsExactly(25);
        assertThat(catalog.version()).isNotEqualTo(prepare(both).version());
    }

    @Test
    void aHashThatDiffersInOneSourceRefusesTheWholeCatalog() throws IOException {
        CatalogManifest.Entry entry = entry(first(), second());
        // the second file changes after the manifest was written
        Files.writeString(raw.resolve("second.jsonl"), line("Tampered", "REGISTERED", "[25]", "7") + "\n");

        assertThatThrownBy(() -> prepare(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("source 'internal'").hasMessageContaining("SHA-256")
                .hasMessageContaining("second.jsonl");
    }

    @Test
    void aMissingSourceFileRefusesTheWholeCatalog() throws IOException {
        CatalogManifest.Entry entry = entry(first(), second());
        Files.delete(raw.resolve("first.jsonl"));

        assertThatThrownBy(() -> prepare(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("source 'uspto'").hasMessageContaining("first.jsonl");
    }

    @Test
    void aBadLineNamesTheSourceAndTheLineAndNotTheMark() throws IOException {
        CatalogManifest.Source bad = source("internal", "second.jsonl",
                line("Zorvex", "REGISTERED", "[25]", "1") + "\n"
                        + "{\"mark\":\"SecretMark\",\"status\":\"FAMOUS\",\"nice_classes\":[25],\"source_id\":\"2\"}\n",
                "terms of second", LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> prepare(entry(first(), bad))).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("source 'internal'").hasMessageContaining("line 2")
                .hasMessageNotContaining("SecretMark");
    }

    @Test
    void aSourceThatKeepsNothingIsFineWhenAnotherKeepsSomething() throws IOException {
        CatalogManifest.Source none = source("internal", "second.jsonl",
                line("Only Nine", "REGISTERED", "[9]", "1") + "\n", "t", LocalDate.of(2026, 9, 1));

        PreparedCatalog catalog = prepare(entry(first(), none));

        assertThat(catalog.entries()).extracting(e -> e.mark().normalized()).containsExactly("brask", "zorvex");
        assertThatThrownBy(() -> prepare(entry(none))).hasMessageContaining("no record is left");
    }

    @Test
    void rejectsSourceTextThatDoesNotFitTheCatalogTable() throws IOException {
        CatalogManifest.Source longName = new CatalogManifest.Source("uspto", "first.jsonl", "n".repeat(150), "u", "l",
                LocalDate.of(2026, 10, 30), first().sha256());
        CatalogManifest.Source other = new CatalogManifest.Source("internal", "second.jsonl", "m".repeat(150), "u", "l",
                LocalDate.of(2026, 10, 30), second().sha256());

        assertThatThrownBy(() -> prepare(entry(longName, other))).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("source_name").hasMessageContaining("200");
    }

    // --- the one-file form keeps working and keeps its version ---

    @Test
    void theOneFileFormStoresItsIdsAsTheyAreAndKeepsItsDigest() throws IOException {
        CatalogManifest.Source one = first();
        CatalogManifest.Entry legacy = CatalogManifest.Entry.single("pod-1", one.file(), CatalogImport.FORMAT_VERSION,
                one.sourceName(), one.url(), one.license(), one.sourceDate(), one.sha256(), Set.of(25, 35),
                Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING));

        PreparedCatalog catalog = prepare(legacy);

        assertThat(catalog.entries().get(1).mark().sourceIds()).containsExactly("1001");
        assertThat(catalog.sourceName()).isEqualTo("Source uspto");
        assertThat(catalog.fileSha256()).isEqualTo(one.sha256());
        // the text of the PR A2 digest, written out again: the versions imported before PR A2b must still be found
        String text = "listing-guard/trademark-catalog-identity/v1\n"
                + "label=pod-1\n"
                + "source_name=Source uspto\n"
                + "url=https://example.invalid/uspto\n"
                + "license=terms of first\n"
                + "source_date=2026-10-30\n"
                + "file_sha256=" + one.sha256() + "\n"
                + "format_version=jsonl-1\n"
                + "filters={\"include_classes\":[25,35],\"include_status\":[\"REGISTERED\",\"PENDING\"]}\n"
                + "pack_id=" + identity.packId() + "\n"
                + "normalization_version=" + identity.normalizationVersion() + "\n"
                + "normalization_hash=" + identity.normalizationHash() + "\n";
        assertThat(catalog.version()).isEqualTo("pod-1-"
                + CatalogImport.sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 12));
    }

    @Test
    void theSameFileAsASingleKeyedSourceIsAnotherCatalog() throws IOException {
        CatalogManifest.Source one = first();
        CatalogManifest.Entry legacy = CatalogManifest.Entry.single("pod-1", one.file(), CatalogImport.FORMAT_VERSION,
                one.sourceName(), one.url(), one.license(), one.sourceDate(), one.sha256(), Set.of(25, 35),
                Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING));

        PreparedCatalog plain = prepare(legacy);
        PreparedCatalog keyed = prepare(entry(one));

        assertThat(keyed.version()).isNotEqualTo(plain.version());
        assertThat(keyed.entries().get(1).mark().sourceIds()).containsExactly("uspto:1001");
    }

    @Test
    void anEntryNeedsAKeyForEverySourceWhenThereAreSeveral() throws IOException {
        CatalogManifest.Source one = first();
        CatalogManifest.Source unkeyed = new CatalogManifest.Source(null, "second.jsonl", "s", "u", "l",
                LocalDate.of(2026, 9, 1), second().sha256());

        assertThatThrownBy(() -> entry(one, unkeyed)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> entry(unkeyed, unkeyed)).isInstanceOf(CatalogFormatException.class);
        assertThatThrownBy(() -> entry()).isInstanceOf(CatalogFormatException.class);
    }
}
