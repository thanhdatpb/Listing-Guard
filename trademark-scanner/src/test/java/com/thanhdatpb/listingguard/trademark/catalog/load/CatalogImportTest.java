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

/** Synthetic marks only. */
class CatalogImportTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TextNormalizer normalizer = new TextNormalizer(ruleset.normalization());
    private final NormalizationIdentity identity = NormalizationIdentity.of(ruleset);

    @TempDir
    Path raw;

    private static String line(String mark, String status, String classes, String sourceId) {
        return "{\"mark\":\"" + mark + "\",\"status\":\"" + status + "\",\"nice_classes\":" + classes
                + ",\"source_id\":\"" + sourceId + "\"}";
    }

    private CatalogManifest.Entry entry(String content, Set<Integer> classes, Set<MarkStatus> statuses)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(raw.resolve("marks.jsonl"), bytes);
        return new CatalogManifest.Entry("synthetic-1", "marks.jsonl", CatalogImport.FORMAT_VERSION, "Synthetic set",
                "https://example.invalid/synthetic", "test only", LocalDate.of(2026, 10, 30),
                CatalogImport.sha256(bytes), classes, statuses);
    }

    private CatalogManifest.Entry entry(String content) throws IOException {
        return entry(content, Set.of(25, 35), Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING));
    }

    private PreparedCatalog prepare(CatalogManifest.Entry entry) {
        return CatalogImport.prepare(entry, raw, normalizer, identity);
    }

    @Test
    void readsFiltersAndCountsRecords() throws IOException {
        String data = String.join("\n",
                line("Zorvex", "REGISTERED", "[25]", "a1"),
                line("Quillmark", "PENDING", "[35, 9]", "a2"),
                line("Old Brand", "CANCELLED", "[25]", "a3"),
                line("Other Class", "REGISTERED", "[9]", "a4"),
                "",
                line("Brask", "REGISTERED", "[25]", "a5")) + "\n";

        PreparedCatalog catalog = prepare(entry(data));

        assertThat(catalog.recordsRead()).isEqualTo(5);
        assertThat(catalog.recordsFiltered()).isEqualTo(2);
        assertThat(catalog.recordsMerged()).isZero();
        assertThat(catalog.recordsLoaded()).isEqualTo(3);
        assertThat(catalog.entries()).extracting(e -> e.mark().normalized()).containsExactly("brask", "quillmark",
                "zorvex");
        assertThat(catalog.fileSha256()).isEqualTo(entry(data).sha256());
        assertThat(catalog.filtersJson()).isEqualTo("{\"include_classes\":[25,35],\"include_status\":[\"REGISTERED\",\"PENDING\"]}");
    }

    @Test
    void aRecordIsKeptWhenAnyClassMatches() throws IOException {
        PreparedCatalog catalog = prepare(entry(line("Zorvex", "REGISTERED", "[9, 42, 35]", "a1") + "\n"));

        assertThat(catalog.entries().get(0).mark().niceClasses()).containsExactly(9, 35, 42);
    }

    @Test
    void theClassAndStatusFiltersComeFromTheManifest() throws IOException {
        String data = line("Zorvex", "REGISTERED", "[9]", "a1") + "\n" + line("Brask", "EXPIRED", "[25]", "a2") + "\n";

        PreparedCatalog catalog = prepare(entry(data, Set.of(9, 25), Set.of(MarkStatus.REGISTERED, MarkStatus.EXPIRED)));

        assertThat(catalog.recordsFiltered()).isZero();
        assertThat(catalog.recordsLoaded()).isEqualTo(2);
        assertThat(entry(data, Set.of(9), Set.of(MarkStatus.REGISTERED)).filtersJson())
                .isEqualTo("{\"include_classes\":[9],\"include_status\":[\"REGISTERED\"]}");
    }

    @Test
    void recordsWithTheSameNormalizedMarkAreMerged() throws IOException {
        String data = String.join("\n",
                line("ZORVEX", "PENDING", "[35]", "b2"),
                "{\"mark\":\"Zorvex!\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"b3\",\"common_word\":true}",
                line("zorvex", "REGISTERED", "[25, 9]", "b1"),
                line("Zorvex", "CANCELLED", "[25]", "b0")) + "\n";

        PreparedCatalog catalog = prepare(entry(data));

        assertThat(catalog.recordsRead()).isEqualTo(4);
        assertThat(catalog.recordsFiltered()).isEqualTo(1);
        assertThat(catalog.recordsMerged()).isEqualTo(2);
        assertThat(catalog.recordsLoaded()).isEqualTo(1);
        PreparedCatalog.Entry merged = catalog.entries().get(0);
        CatalogMark mark = merged.mark();
        assertThat(mark.normalized()).isEqualTo("zorvex");
        assertThat(mark.niceClasses()).containsExactly(9, 25, 35);
        assertThat(mark.commonWord()).isTrue();
        assertThat(mark.status()).isEqualTo(MarkStatus.REGISTERED);
        // "ZORVEX" < "Zorvex!" < "zorvex": the smallest display form by String.compareTo, with its source id
        assertThat(mark.display()).isEqualTo("ZORVEX");
        assertThat(merged.sourceId()).isEqualTo("b2");
    }

    @Test
    void theDisplayTieGoesToTheSmallerSourceId() throws IOException {
        String data = line("Zorvex", "REGISTERED", "[25]", "z9") + "\n" + line("Zorvex", "REGISTERED", "[25]", "a1") + "\n";

        assertThat(prepare(entry(data)).entries().get(0).sourceId()).isEqualTo("a1");
    }

    @Test
    void theOutcomeDoesNotDependOnTheOrderOfTheLines() throws IOException {
        String a = line("Zorvex", "PENDING", "[25]", "1") + "\n" + line("ZORVEX", "REGISTERED", "[35]", "2") + "\n"
                + line("Brask", "REGISTERED", "[25]", "3") + "\n";
        String b = line("Brask", "REGISTERED", "[25]", "3") + "\n" + line("ZORVEX", "REGISTERED", "[35]", "2") + "\n"
                + line("Zorvex", "PENDING", "[25]", "1") + "\n";

        assertThat(prepare(entry(a)).entries()).isEqualTo(prepare(entry(b)).entries());
    }

    @Test
    void marksAreNormalizedWithTheRulesetNormalizer() throws IOException {
        PreparedCatalog catalog = prepare(entry(line("Zörv3x-Tools", "REGISTERED", "[25]", "a1") + "\n"));

        assertThat(catalog.entries().get(0).mark().normalized()).isEqualTo("zorvex tools");
        assertThat(catalog.entries().get(0).mark().display()).isEqualTo("Zörv3x-Tools");
    }

    // --- rejected data: the whole batch, with the line number and without the text of the mark ---

    private void assertRejected(String content, String lineNumber) throws IOException {
        CatalogManifest.Entry entry = entry(content);
        assertThatThrownBy(() -> prepare(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining(lineNumber).hasMessageNotContaining("SecretMark");
    }

    @Test
    void rejectsAMissingFieldWithItsLine() throws IOException {
        assertRejected(line("Zorvex", "REGISTERED", "[25]", "a1") + "\n"
                + "{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[25]}\n", "line 2");
        assertRejected("{\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"nice_classes\":[25],\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"source_id\":\"x\"}\n", "line 1");
    }

    @Test
    void rejectsAWrongType() throws IOException {
        assertRejected("{\"mark\":5,\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":\"25\",\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[\"25\"],\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[25.5],\"source_id\":\"x\"}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":7}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\",\"common_word\":\"yes\"}\n", "line 1");
    }

    @Test
    void rejectsAClassOutsideOneToFortyFive() throws IOException {
        assertRejected(line("SecretMark", "REGISTERED", "[0]", "x") + "\n", "line 1");
        assertRejected(line("Zorvex", "REGISTERED", "[25]", "a") + "\n" + line("SecretMark", "REGISTERED", "[46]", "x") + "\n", "line 2");
        assertRejected(line("SecretMark", "REGISTERED", "[]", "x") + "\n", "line 1");
        assertRejected(line("SecretMark", "REGISTERED", "[-3]", "x") + "\n", "line 1");
    }

    @Test
    void rejectsAnUnknownStatus() throws IOException {
        assertRejected(line("SecretMark", "FAMOUS", "[25]", "x") + "\n", "line 1");
        assertRejected(line("SecretMark", "registered", "[25]", "x") + "\n", "line 1");
    }

    @Test
    void rejectsAMarkThatNormalizesToNothing() throws IOException {
        assertRejected(line("!!! ...", "REGISTERED", "[25]", "x") + "\n", "line 1");
        assertThatThrownBy(() -> prepare(entry(line("!!!", "REGISTERED", "[25]", "x") + "\n")))
                .hasMessageContaining("no letters or digits");
    }

    @Test
    void rejectsBadJsonAnUnknownKeyAndANonObject() throws IOException {
        assertRejected("{not json}\n", "line 1");
        assertRejected("[1,2]\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\",\"extra\":1}\n", "line 1");
        assertRejected("{\"mark\":\"SecretMark\",\"mark\":\"b\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"x\"}\n", "line 1");
    }

    @Test
    void aBadLineRejectsTheBatchEvenWhenTheFiltersWouldDropIt() throws IOException {
        String data = line("Zorvex", "REGISTERED", "[25]", "a") + "\n" + line("SecretMark", "FAMOUS", "[9]", "x") + "\n";

        assertThatThrownBy(() -> prepare(entry(data))).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void rejectsAFileThatIsNotUtf8() throws IOException {
        byte[] bytes = new byte[]{'{', (byte) 0xC3, (byte) 0x28, '}', '\n'};
        Files.write(raw.resolve("marks.jsonl"), bytes);
        CatalogManifest.Entry entry = new CatalogManifest.Entry("synthetic-1", "marks.jsonl", CatalogImport.FORMAT_VERSION,
                "s", "u", "l", LocalDate.of(2026, 10, 30), CatalogImport.sha256(bytes), Set.of(25),
                Set.of(MarkStatus.REGISTERED));

        assertThatThrownBy(() -> prepare(entry)).hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsAFileWhoseHashDiffersFromTheManifest() throws IOException {
        CatalogManifest.Entry entry = entry(line("Zorvex", "REGISTERED", "[25]", "a") + "\n");
        Files.writeString(raw.resolve("marks.jsonl"), line("Brask", "REGISTERED", "[25]", "a") + "\n");

        assertThatThrownBy(() -> prepare(entry)).isInstanceOf(CatalogFormatException.class)
                .hasMessageContaining("SHA-256").hasMessageContaining(entry.sha256());
    }

    @Test
    void rejectsAnImportThatKeepsNothing() throws IOException {
        CatalogManifest.Entry entry = entry(line("Zorvex", "REGISTERED", "[9]", "a") + "\n");

        assertThatThrownBy(() -> prepare(entry)).hasMessageContaining("no record is left");
        assertThatThrownBy(() -> prepare(entry(""))).hasMessageContaining("no record is left");
    }

    @Test
    void rejectsAFormatVersionTheCodeDoesNotRead() throws IOException {
        CatalogManifest.Entry good = entry(line("Zorvex", "REGISTERED", "[25]", "a") + "\n");
        CatalogManifest.Entry other = new CatalogManifest.Entry(good.version(), good.file(), "jsonl-2", good.sourceName(),
                good.url(), good.license(), good.sourceDate(), good.sha256(), good.includeClasses(), good.includeStatus());

        assertThatThrownBy(() -> prepare(other)).hasMessageContaining("format_version");
    }

    @Test
    void readsOnlyAFileInsideTheRawDirectory() throws IOException {
        CatalogManifest.Entry good = entry(line("Zorvex", "REGISTERED", "[25]", "a") + "\n");
        Path outside = raw.getParent().resolve("outside-" + System.nanoTime() + ".jsonl");
        Files.writeString(outside, line("Zorvex", "REGISTERED", "[25]", "a") + "\n");
        try {
            CatalogManifest.Entry escaping = new CatalogManifest.Entry(good.version(),
                    "../" + outside.getFileName(), good.formatVersion(), good.sourceName(), good.url(), good.license(),
                    good.sourceDate(), good.sha256(), good.includeClasses(), good.includeStatus());
            CatalogManifest.Entry missing = new CatalogManifest.Entry(good.version(), "absent.jsonl",
                    good.formatVersion(), good.sourceName(), good.url(), good.license(), good.sourceDate(),
                    good.sha256(), good.includeClasses(), good.includeStatus());

            assertThatThrownBy(() -> prepare(escaping)).isInstanceOf(CatalogFormatException.class)
                    .hasMessageContaining("is not in");
            assertThatThrownBy(() -> prepare(missing)).isInstanceOf(CatalogFormatException.class);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // --- the version identifies the import ---

    @Test
    void theVersionIsTheLabelPlusADigestOfTheIdentity() throws IOException {
        String data = line("Zorvex", "REGISTERED", "[25]", "a") + "\n";

        PreparedCatalog first = prepare(entry(data));
        PreparedCatalog again = prepare(entry(data));

        assertThat(first.version()).matches("synthetic-1-[0-9a-f]{12}").isEqualTo(again.version());
        assertThat(first.label()).isEqualTo("synthetic-1");
    }

    @Test
    void anyChangeOfTheFileFiltersSourceOrNormalizationIsAnotherVersion() throws IOException {
        String data = line("Zorvex", "REGISTERED", "[25]", "a") + "\n" + line("Brask", "PENDING", "[35]", "b") + "\n";
        CatalogManifest.Entry base = entry(data);
        String baseVersion = prepare(base).version();

        assertThat(prepare(entry(data + line("Quillmark", "REGISTERED", "[25]", "c") + "\n")).version())
                .isNotEqualTo(baseVersion);
        entry(data);
        assertThat(prepare(entry(data, Set.of(25), Set.of(MarkStatus.REGISTERED, MarkStatus.PENDING))).version())
                .isNotEqualTo(baseVersion);
        assertThat(prepare(entry(data, Set.of(25, 35), Set.of(MarkStatus.REGISTERED))).version())
                .isNotEqualTo(baseVersion);
        CatalogManifest.Entry restored = entry(data);

        assertThat(prepare(withLicense(restored, "another license")).version()).isNotEqualTo(baseVersion);
        assertThat(prepare(withSourceDate(restored, LocalDate.of(2026, 10, 31))).version()).isNotEqualTo(baseVersion);

        TrademarkRuleset edited = TestRulesets.variant("final_threshold: 0.80", "final_threshold: 0.81");
        assertThat(CatalogImport.prepare(restored, raw, normalizer, NormalizationIdentity.of(edited)).version())
                .isNotEqualTo(baseVersion);
        NormalizationIdentity newerNormalizer = new NormalizationIdentity(identity.packId(), identity.rulesetVersion(),
                identity.rulesetContentHash(), "normalization-2");
        assertThat(CatalogImport.prepare(restored, raw, normalizer, newerNormalizer).version()).isNotEqualTo(baseVersion);
    }

    private static CatalogManifest.Entry withLicense(CatalogManifest.Entry e, String license) {
        return new CatalogManifest.Entry(e.version(), e.file(), e.formatVersion(), e.sourceName(), e.url(), license,
                e.sourceDate(), e.sha256(), e.includeClasses(), e.includeStatus());
    }

    private static CatalogManifest.Entry withSourceDate(CatalogManifest.Entry e, LocalDate date) {
        return new CatalogManifest.Entry(e.version(), e.file(), e.formatVersion(), e.sourceName(), e.url(), e.license(),
                date, e.sha256(), e.includeClasses(), e.includeStatus());
    }

    @Test
    void theNormalizationIdentityIsTheRulesetAndNormalizerVersion() {
        assertThat(identity.packId()).isEqualTo("trademark-risk");
        assertThat(identity.rulesetVersion()).isEqualTo(ruleset.version());
        assertThat(identity.rulesetContentHash()).isEqualTo(ruleset.contentHash());
        assertThat(identity.normalizationVersion()).isEqualTo(TextNormalizer.VERSION);
        assertThat(List.of(identity.packId())).isNotEmpty();
    }
}
