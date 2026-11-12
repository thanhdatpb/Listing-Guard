package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.caseFileOn;
import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchiveException.Kind;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link UsptoArchive}: the XML of a zip is read as it is stored, never extracted, and a crafted or damaged archive is
 * refused (ADR-009, PR A2d). Every archive is built here from synthetic XML.
 */
class UsptoArchiveTest {

    private static final UsptoArchive.Limits DEFAULTS = UsptoArchive.Limits.defaults();

    @TempDir
    Path dir;

    private static String day(String date, String serialPrefix) {
        return file(caseFileOn(serialPrefix + "01", date, "Zorvex", "4000", "700", "025"),
                caseFileOn(serialPrefix + "02", date, "Brasktone", "4000", "686", "021"));
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path path = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    private Path zip(String name, String entryName, String content) throws IOException {
        return zip(name, Map.of(entryName, content.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] read(UsptoCatalogConverter.Source source) throws IOException {
        try (InputStream in = source.open()) {
            return in.readAllBytes();
        }
    }

    private static UsptoCatalogConverter converter() throws IOException {
        UsptoStatusMap map = UsptoTestXml.statusMap();
        String ruleset;
        try (InputStream in = UsptoArchiveTest.class.getResourceAsStream("/rulesets/trademark-risk-2026-11b.yaml")) {
            ruleset = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new UsptoCatalogConverter(map, UsptoConvertFilters.loadClasspath("uspto/uspto-convert-filters-2026-11.yaml"),
                new TextNormalizer(TrademarkRulesetLoader.parse(ruleset).normalization()),
                new UsptoCatalogConverter.Options(false, true, 1000));
    }

    @Test
    void theXmlOfAnArchiveIsReadAsItIsWithoutExtractingIt() throws IOException {
        String xml = day("20241101", "970000");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("notes/", new byte[0]);
        entries.put("notes/README.txt", "not read".getBytes(StandardCharsets.UTF_8));
        entries.put("apc241101.xml", xml.getBytes(StandardCharsets.UTF_8));
        Path archive = zip("apc241101.zip", entries);

        UsptoArchive.Inspection inspection = UsptoArchive.inspect(archive, DEFAULTS);
        List<UsptoCatalogConverter.Source> sources = UsptoArchive.sources(archive, "daily/apc241101.zip", DEFAULTS);

        assertThat(inspection.xmlEntries()).extracting(UsptoArchive.Entry::name).containsExactly("apc241101.xml");
        assertThat(inspection.otherEntries()).isEqualTo(2);
        assertThat(inspection.uncompressedBytes()).isEqualTo(xml.getBytes(StandardCharsets.UTF_8).length);
        assertThat(sources).extracting(UsptoCatalogConverter.Source::name)
                .containsExactly("daily/apc241101.zip!apc241101.xml");
        assertThat(new String(read(sources.get(0)), StandardCharsets.UTF_8)).isEqualTo(xml);
        assertThat(read(sources.get(0))).as("a source can be opened again, once for each pass")
                .isEqualTo(xml.getBytes(StandardCharsets.UTF_8));
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).as("nothing was written next to the archive")
                    .containsExactly("apc241101.zip");
        }
    }

    @Test
    void anArchiveConvertsExactlyAsItsXmlDoes() throws IOException {
        String first = day("20241101", "970000");
        String second = file(caseFileOn("97000002", "20241102", "Brasktone", "4000", "710", "021"));
        Path a = zip("apc241101.zip", "apc241101.xml", first);
        Path b = zip("apc241102.zip", "apc241102.xml", second);

        ByteArrayOutputStream fromZip = new ByteArrayOutputStream();
        List<UsptoCatalogConverter.Source> zipped = new java.util.ArrayList<>(UsptoArchive.sources(b, "apc241102.zip", DEFAULTS));
        zipped.addAll(UsptoArchive.sources(a, "apc241101.zip", DEFAULTS));
        ConversionReport zipReport = converter().convert(zipped, fromZip);

        ByteArrayOutputStream fromXml = new ByteArrayOutputStream();
        ConversionReport xmlReport = converter().convert(List.of(UsptoTestXml.source("apc241101.xml", first),
                UsptoTestXml.source("apc241102.xml", second)), fromXml);

        assertThat(fromZip.toByteArray()).isEqualTo(fromXml.toByteArray());
        assertThat(zipReport.recordsWritten()).isEqualTo(1).isEqualTo(xmlReport.recordsWritten());
        assertThat(zipReport.superseded()).isEqualTo(1);
        assertThat(zipReport.deadRemoved()).isEqualTo(1);
        assertThat(zipReport.files()).extracting(ConversionReport.FileReport::name)
                .containsExactly("apc241101.zip!apc241101.xml", "apc241102.zip!apc241102.xml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../apc241101.xml", "a/../../apc241101.xml", "/etc/apc241101.xml", "C:/apc241101.xml",
            "c:apc241101.xml", "dir\\apc241101.xml", "apc\u0001.xml", ".."})
    void anEntryNameThatCouldLeaveAFolderIsRefused(String name) throws IOException {
        Path archive = zip("evil.zip", Map.of(name, day("20241101", "970000").getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNSAFE))
                .hasMessageContaining("evil.zip");
        assertThatThrownBy(() -> UsptoArchive.sources(archive, "evil.zip", DEFAULTS))
                .isInstanceOf(UsptoArchiveException.class);
    }

    @Test
    void aSafeNestedNameIsAccepted() {
        assertThat(UsptoArchive.nameProblem("2024/apc241101.xml")).isNull();
        assertThat(UsptoArchive.nameProblem("apc..241101.xml")).as("two dots inside a name are not a segment").isNull();
    }

    @Test
    void tooManyEntriesAreRefused() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            entries.put("part" + i + ".xml", day("20241101", "9700000" + i).getBytes(StandardCharsets.UTF_8));
        }
        Path archive = zip("many.zip", entries);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, new UsptoArchive.Limits(2, 1L << 30, 200)))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("more than 2 entries");
        assertThat(UsptoArchive.inspect(archive, new UsptoArchive.Limits(3, 1L << 30, 200)).xmlEntries()).hasSize(3);
    }

    @Test
    void anArchiveWithoutXmlIsRefused() throws IOException {
        Path archive = zip("notes.zip", "README.txt", "no data");

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("no .xml entry");
    }

    @Test
    void anEntryLargerThanTheLimitIsRefusedFromItsHeader() throws IOException {
        String xml = day("20241101", "970000");
        Path archive = zip("large.zip", "apc241101.xml", xml);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, new UsptoArchive.Limits(16, 100, 200)))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("over the limit of 100");
    }

    @Test
    void aZipBombIsRefusedFromItsHeader() throws IOException {
        // 16 MiB of spaces inside a valid file compress to a few kilobytes: far more than 200 to 1
        String xml = file(caseFileOn("97000001", "20241101", "Zorvex", "4000", "700", "025")) + " ".repeat(16 << 20);
        Path archive = zip("bomb.zip", "apc241101.xml", xml);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("more than 200 to 1");
    }

    @Test
    void aHeaderThatUnderstatesTheSizeDoesNotGetPastTheStream() throws IOException {
        String xml = file(caseFileOn("97000001", "20241101", "Zorvex", "4000", "700", "025")) + " ".repeat(16 << 20);
        Path archive = zip("lying.zip", "apc241101.xml", xml);
        // the central directory now says 1,000 bytes: the declared ratio looks harmless
        patchCentralDirectory(archive, 24, 1_000);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("more than its compressed size allows");
        UsptoCatalogConverter.Source source = UsptoArchive.sources(archive, "lying.zip", DEFAULTS).get(0);
        assertThatThrownBy(() -> read(source)).isInstanceOf(UsptoArchiveException.class);
    }

    @Test
    void anEntryOverTheSizeLimitIsStoppedWhileItIsRead() throws IOException {
        String xml = day("20241101", "970000");
        int real = xml.getBytes(StandardCharsets.UTF_8).length;
        Path archive = zip("understated.zip", "apc241101.xml", xml);
        patchCentralDirectory(archive, 24, 100);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, new UsptoArchive.Limits(16, real - 1, 200)))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.unsafe()).isTrue())
                .hasMessageContaining("produces more than " + (real - 1) + " bytes");
    }

    @Test
    void aDamagedEntryIsFoundBeforeItIsUsed() throws IOException {
        String xml = day("20241101", "970000");
        Path archive = zip("damaged.zip", "apc241101.xml", xml);
        // the CRC-32 recorded in the central directory no longer matches the content
        patchCentralDirectory(archive, 16, 0x12345678);

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DAMAGED))
                .hasMessageContaining("CRC");
    }

    @Test
    void aTruncatedFileIsDamaged() throws IOException {
        Path archive = zip("truncated.zip", "apc241101.xml", day("20241101", "970000"));
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DAMAGED))
                .hasMessageContaining("not a readable zip archive");
    }

    @Test
    void somethingThatIsNotAZipIsDamaged() throws IOException {
        Path archive = dir.resolve("apc241101.zip");
        Files.writeString(archive, "<html>an error page instead of the file</html>");

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS))
                .isInstanceOfSatisfying(UsptoArchiveException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DAMAGED));
    }

    @Test
    void twoEntriesWithTheSameNameAreRefused() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("apc241101.xml", day("20241101", "970000").getBytes(StandardCharsets.UTF_8));
        entries.put("apc241102.xml", day("20241102", "970001").getBytes(StandardCharsets.UTF_8));
        Path archive = zip("twice.zip", entries);
        byte[] bytes = Files.readAllBytes(archive);
        String text = new String(bytes, StandardCharsets.ISO_8859_1).replace("apc241102.xml", "apc241101.xml");
        Files.write(archive, text.getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> UsptoArchive.inspect(archive, DEFAULTS)).isInstanceOf(UsptoArchiveException.class);
    }

    @Test
    void isArchiveLooksAtTheExtensionOnly() {
        assertThat(UsptoArchive.isArchive("apc241101.ZIP")).isTrue();
        assertThat(UsptoArchive.isArchive("apc241101.xml")).isFalse();
    }

    /** Writes a 32-bit little-endian value at {@code offset} of the (single) central directory header of the archive. */
    private static void patchCentralDirectory(Path archive, int offset, int value) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        int at = -1;
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x01 && bytes[i + 3] == 0x02) {
                at = i;
                break;
            }
        }
        assertThat(at).as("a central directory header").isNotNegative();
        for (int k = 0; k < 4; k++) {
            bytes[at + offset + k] = (byte) (value >>> (8 * k));
        }
        Files.write(archive, bytes);
    }
}
