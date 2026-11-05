package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogImport;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The USPTO Trademark Applications Daily converter (ADR-009, PR A2b). Every file here is small synthetic XML written
 * by hand to follow the public DTD V2.0; marks are invented. Nothing was checked against a real USPTO file.
 */
class UsptoDailyConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UsptoStatusMap statusMap = UsptoStatusMap.loadClasspath("uspto/uspto-status-map-2026-11.yaml");
    private final TextNormalizer normalizer = new TextNormalizer(TestRulesets.shipped().normalization());

    @TempDir
    Path dir;

    // --- synthetic XML following the DTD ---

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String element(String name, String value) {
        return value == null ? "" : "<" + name + ">" + esc(value) + "</" + name + ">";
    }

    /** A case-file in the order of the DTD; a null value leaves the element out. */
    private static String caseFile(String serial, String mark, String drawing, String status, String... classes) {
        String classification = "<classification><international-code-total-no>" + classes.length
                + "</international-code-total-no>"
                + java.util.Arrays.stream(classes).map(c -> element("international-code", c)).collect(Collectors.joining())
                // the status of the class: a decoy, the header status is the one that counts
                + "<status-code>6</status-code><status-date>20200101</status-date></classification>";
        return caseFileWith(serial, mark, drawing, status, "<classifications>" + classification + "</classifications>");
    }

    private static String caseFileWith(String serial, String mark, String drawing, String status, String classifications) {
        return "<case-file>" + element("serial-number", serial)
                + "<registration-number>0000000</registration-number><transaction-date>20241101</transaction-date>"
                + "<case-file-header><filing-date>20200101</filing-date><registration-date>00000000</registration-date>"
                + element("status-code", status) + "<status-date>20240101</status-date>"
                + element("mark-identification", mark) + element("mark-drawing-code", drawing)
                + "</case-file-header>"
                + "<case-file-statements><case-file-statement><type-code>GS0251</type-code><text>Clothing</text>"
                + "</case-file-statement></case-file-statements>"
                + "<foreign-applications><foreign-application><filing-date>20190101</filing-date>"
                + "<country>XX</country><registration-number>9999999</registration-number>"
                + "</foreign-application></foreign-applications>"
                + classifications
                + "<case-file-owners><case-file-owner><party-name>Example Owner</party-name><country>XX</country>"
                + "</case-file-owner></case-file-owners></case-file>";
    }

    private static String fileVersion(String versionNo, String versionDate, String... caseFiles) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trademark-applications-daily>"
                + "<version><version-no>" + versionNo + "</version-no><version-date>" + versionDate
                + "</version-date></version><application-information><file-segments>"
                + "<file-segment>TRMK</file-segment><action-keys><action-key>NA</action-key>"
                + String.join("", caseFiles) + "</action-keys></file-segments></application-information>"
                + "</trademark-applications-daily>";
    }

    private static String file(String... caseFiles) {
        return fileVersion("2.0", "20041108", caseFiles);
    }

    private record Converted(ConversionReport report, List<JsonNode> records, String output) {
    }

    private Converted convert(String xml) throws IOException {
        return convert(xml, false);
    }

    private Converted convert(String xml, boolean allowOtherVersion) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConversionReport report = new UsptoDailyConverter(statusMap, normalizer, allowOtherVersion)
                .convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), out);
        String output = out.toString(StandardCharsets.UTF_8);
        List<JsonNode> records = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isEmpty()) {
                records.add(JSON.readTree(line));
            }
        }
        return new Converted(report, records, output);
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // --- the record ---

    @Test
    void convertsARecordToJsonlAndCountsIt() throws IOException {
        Converted c = convert(file(caseFile("97000001", "Zorvex", "4000", "700", "025")));

        assertThat(c.output()).isEqualTo(
                "{\"mark\":\"Zorvex\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000001\"}\n");
        assertThat(c.report().recordsRead()).isEqualTo(1);
        assertThat(c.report().recordsWritten()).isEqualTo(1);
        assertThat(c.report().dropped()).isEmpty();
        assertThat(c.report().versionNo()).isEqualTo("2.0");
        assertThat(c.report().versionDate()).isEqualTo("20041108");
        assertThat(c.report().dataAvailable()).isTrue();
        assertThat(c.report().statusMapVersion()).isEqualTo(statusMap.version());
        assertThat(c.report().statusMapHash()).isEqualTo(statusMap.contentHash());
    }

    @Test
    void readsOnlyTheElementsOfTheCaseFileThatItNames() throws IOException {
        // the class status 6, the foreign application's registration number and the owner's country are decoys
        Converted c = convert(file(caseFile("97000002", "Brask", "5R12", "686", "035")));

        assertThat(c.records()).hasSize(1);
        assertThat(c.records().get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(c.records().get(0).get("source_id").asText()).isEqualTo("97000002");
    }

    @Test
    void unitesAndSortsTheClassesOfSeveralClassifications() throws IOException {
        String classifications = "<classifications>"
                + "<classification><international-code>025</international-code><international-code>035</international-code>"
                + "<us-code>039</us-code></classification>"
                + "<classification><international-code>009</international-code><international-code>025</international-code>"
                + "</classification></classifications>";

        Converted c = convert(file(caseFileWith("97000003", "Quillfeather", "4000", "700", classifications)));

        assertThat(c.records().get(0).get("nice_classes")).extracting(JsonNode::asInt).containsExactly(9, 25, 35);
    }

    @Test
    void leavesOutCodesThatAreNotNiceClasses() throws IOException {
        // A and B (certification marks), 200 (collective membership), 046 and 000 are not classes 1 to 45
        Converted kept = convert(file(caseFile("97000004", "Zorvex", "4000", "700", "025", "A", "B", "200", "046", "000",
                "034")));

        assertThat(kept.records().get(0).get("nice_classes")).extracting(JsonNode::asInt).containsExactly(25, 34);
        assertThat(kept.report().ignoredClassCodes()).isEqualTo(5);

        Converted dropped = convert(file(caseFile("97000005", "Zorvex", "4000", "700", "A", "200")));

        assertThat(dropped.records()).isEmpty();
        assertThat(dropped.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.NO_VALID_NICE_CLASS, 1L));
        assertThat(dropped.report().ignoredClassCodes()).as("only the codes of written records").isZero();
    }

    @Test
    void aRecordWithoutAnyClassificationIsDropped() throws IOException {
        Converted c = convert(file(caseFileWith("97000006", "Zorvex", "4000", "700", "")));

        assertThat(c.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.NO_VALID_NICE_CLASS, 1L));
    }

    @Test
    void readsTextWithEntitiesAndCdata() throws IOException {
        Converted c = convert(file(
                caseFile("97000007", "Quill & Thorn", "4000", "700", "025"),
                caseFileWith("97000008", null, "4000", "700", "<classifications><classification>"
                        + "<international-code>025</international-code></classification></classifications>")
                        .replace("<case-file-header>", "<case-file-header><mark-identification><![CDATA[Brask <One>]]>"
                                + "</mark-identification>")));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("Quill & Thorn", "Brask <One>");
    }

    // --- logo-only marks ---

    @Test
    void keepsTheLetteringDrawingCodesAndDropsTheOthers() throws IOException {
        Converted c = convert(file(
                caseFile("97000011", "Aaa", "1000", "700", "025"),
                caseFile("97000012", "Bbb", "3S08", "700", "025"),
                caseFile("97000013", "Ccc", "4000", "700", "025"),
                caseFile("97000014", "Ddd", "5R12", "700", "025"),
                caseFile("97000015", "Eee", "2000", "700", "025"),
                caseFile("97000016", "Fff", "6000", "700", "025"),
                caseFile("97000017", "Ggg", "X000", "700", "025"),
                caseFile("97000018", "Hhh", null, "700", "025"),
                caseFile("97000019", "Iii", "  ", "700", "025")));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("Aaa", "Bbb", "Ccc", "Ddd");
        assertThat(c.report().dropped()).containsOnly(
                Map.entry(UsptoDailyConverter.LOGO_ONLY, 1L),
                Map.entry(UsptoDailyConverter.NO_DRAWING, 1L),
                Map.entry(UsptoDailyConverter.UNKNOWN_DRAWING_CODE, 1L),
                Map.entry(UsptoDailyConverter.MISSING_DRAWING_CODE, 2L));
    }

    @Test
    void aDesignWithWordsIsKeptOnlyIfItHasMarkText() throws IOException {
        Converted c = convert(file(
                caseFile("97000021", "Zorvex Shield", "3000", "700", "025"),
                caseFile("97000022", "   ", "3000", "700", "025"),
                caseFile("97000023", null, "3000", "700", "025")));

        assertThat(c.records()).hasSize(1);
        assertThat(c.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.BLANK_MARK, 2L));
    }

    // --- marks the import would refuse ---

    @Test
    void dropsAMarkLongerThanTheImportAccepts() throws IOException {
        String atLimit = "a".repeat(CatalogImport.MAX_MARK_CHARS);
        String overLimit = "a".repeat(CatalogImport.MAX_MARK_CHARS + 1);

        Converted c = convert(file(
                caseFile("97000031", atLimit, "4000", "700", "025"),
                caseFile("97000032", overLimit, "4000", "700", "025")));

        assertThat(c.records()).hasSize(1);
        assertThat(c.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.MARK_TOO_LONG, 1L));
    }

    @Test
    void aVeryLongTextNodeIsDroppedWithoutBeingKept() throws IOException {
        Converted c = convert(file(caseFile("97000033", "z".repeat(200_000), "4000", "700", "025")));

        assertThat(c.records()).isEmpty();
        assertThat(c.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.MARK_TOO_LONG, 1L));
    }

    @Test
    void dropsAMarkTheImportWouldRefuseAfterNormalization() throws IOException {
        // no letters or digits: empty once normalized. "ß" becomes "ss": 200 of them are 400 characters.
        Converted c = convert(file(
                caseFile("97000034", "!!! ???", "4000", "700", "025"),
                caseFile("97000035", "ß".repeat(200), "4000", "700", "025"),
                caseFile("97000036", "Zorvex", "4000", "700", "025")));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("Zorvex");
        assertThat(c.report().dropped()).containsOnly(
                Map.entry(UsptoDailyConverter.MARK_EMPTY_AFTER_NORMALIZATION, 1L),
                Map.entry(UsptoDailyConverter.MARK_TOO_LONG_AFTER_NORMALIZATION, 1L));
    }

    // --- status ---

    @Test
    void mapsTheStatusOfTheHeader() throws IOException {
        Converted c = convert(file(
                caseFile("97000041", "Aaa", "4000", "700", "025"),
                caseFile("97000042", "Bbb", "4000", "900", "025"),
                caseFile("97000043", "Ccc", "4000", "710", "025"),
                caseFile("97000044", "Ddd", "4000", "686", "025"),
                caseFile("97000045", "Eee", "4000", "601", "025"),
                caseFile("97000046", "Fff", "4000", "403", "025")));

        assertThat(c.records()).extracting(r -> r.get("status").asText())
                .containsExactly("REGISTERED", "EXPIRED", "CANCELLED", "PENDING", "CANCELLED", "PENDING");
        assertThat(c.report().dropped()).isEmpty();
    }

    @Test
    void dropsAndCountsTheCodesThatCarryNoStatusAndTheOnesNotListed() throws IOException {
        Converted c = convert(file(
                caseFile("97000051", "Aaa", "4000", "000", "025"),
                caseFile("97000052", "Bbb", "4000", "973", "025"),
                caseFile("97000053", "Ccc", "4000", "973", "025"),
                caseFile("97000054", "Ddd", "4000", "999", "025"),
                caseFile("97000055", "Eee", "4000", null, "025"),
                caseFile("97000056", "Fff", "4000", "ABC", "025"),
                caseFile("97000057", "Ggg", "4000", "7", "025")));

        assertThat(c.records()).isEmpty();
        assertThat(c.report().dropped()).containsOnly(
                Map.entry("status_dropped:000", 1L),
                Map.entry("status_dropped:973", 2L),
                Map.entry("status_unmapped:999", 1L),
                Map.entry("status_unmapped:missing", 1L),
                Map.entry("status_unmapped:invalid", 2L));
    }

    @Test
    void everyCodeOfTheMapIsHandled() throws IOException {
        List<String> cases = new ArrayList<>();
        int i = 0;
        for (String code : statusMap.entries().keySet()) {
            cases.add(caseFile(String.format("97%06d", 100 + i++), "Mark" + code.charAt(0) + "xx", "4000", code, "025"));
        }

        Converted c = convert(file(cases.toArray(String[]::new)));

        long dropCodes = statusMap.entries().values().stream()
                .filter(e -> e.target() == UsptoStatusMap.Target.DROP).count();
        assertThat(c.report().recordsRead()).isEqualTo(149);
        assertThat(c.report().recordsWritten()).isEqualTo(149 - dropCodes);
        assertThat(c.report().droppedTotal()).isEqualTo(dropCodes);
    }

    // --- serial number, order, repeats ---

    @Test
    void dropsARecordWithoutAValidSerialNumber() throws IOException {
        Converted c = convert(file(
                caseFile(null, "Aaa", "4000", "700", "025"),
                caseFile("  ", "Bbb", "4000", "700", "025"),
                caseFile("123", "Ccc", "4000", "700", "025"),
                caseFile("9700000A", "Ddd", "4000", "700", "025"),
                caseFile(" 97000061 ", "Eee", "4000", "700", "025")));

        assertThat(c.records()).extracting(r -> r.get("source_id").asText()).containsExactly("97000061");
        assertThat(c.report().dropped()).containsOnly(
                Map.entry(UsptoDailyConverter.MISSING_SERIAL, 2L), Map.entry(UsptoDailyConverter.INVALID_SERIAL, 2L));
    }

    @Test
    void keepsTheOrderAndEveryRecordOfARepeatedSerialNumber() throws IOException {
        // a daily file holds changes: the same serial can come twice. There is no "latest wins" yet (ADR-009, A2c).
        Converted c = convert(file(
                caseFile("97000071", "Zorvex", "4000", "686", "025"),
                caseFile("97000072", "Brask", "4000", "700", "035"),
                caseFile("97000071", "Zorvex", "4000", "710", "025")));

        assertThat(c.records()).extracting(r -> r.get("status").asText()).containsExactly("PENDING", "REGISTERED", "CANCELLED");
        assertThat(c.records()).extracting(r -> r.get("source_id").asText())
                .containsExactly("97000071", "97000072", "97000071");
    }

    @Test
    void aRecordIsCountedUnderTheFirstCheckItFailsAndTheCountsAddUp() throws IOException {
        // a logo with an unmapped status and no class: only the drawing code is reported
        Converted c = convert(file(
                caseFile("97000081", "Aaa", "2000", "999"),
                caseFile("97000082", "Bbb", "4000", "700", "025")));

        assertThat(c.report().dropped()).containsOnly(Map.entry(UsptoDailyConverter.LOGO_ONLY, 1L));
        assertThat(c.report().recordsRead()).isEqualTo(c.report().recordsWritten() + c.report().droppedTotal());
    }

    // --- the file ---

    @Test
    void stopsOnAnotherVersionAndNamesWhatItFound() {
        assertThatThrownBy(() -> convert(fileVersion("1.0", "20050101", caseFile("97000091", "Zorvex", "4000", "700", "025"))))
                .isInstanceOf(UsptoConversionException.class)
                .hasMessageContaining("version-no '1.0'").hasMessageContaining("version-date '20050101'")
                .hasMessageContaining("2.0").hasMessageContaining("allow-other-version")
                .hasMessageNotContaining("Zorvex");
    }

    @Test
    void readsAnotherVersionOnlyWhenAllowedAndSaysSo() throws IOException {
        Converted c = convert(fileVersion("2.1", "20240101", caseFile("97000092", "Zorvex", "4000", "700", "025")), true);

        assertThat(c.records()).hasSize(1);
        assertThat(c.report().versionNo()).isEqualTo("2.1");
        assertThat(c.report().otherVersionAllowed()).isTrue();
        assertThat(convert(file(caseFile("97000093", "Zorvex", "4000", "700", "025")), true)
                .report().otherVersionAllowed()).isFalse();
    }

    @Test
    void refusesAFileWithoutAVersionOrOfAnotherKind() {
        String noVersion = "<trademark-applications-daily><application-information><file-segments/>"
                + "</application-information></trademark-applications-daily>";
        assertThatThrownBy(() -> convert(noVersion)).isInstanceOf(UsptoConversionException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> convert("<trademark-applications-daily/>"))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("no version");
        assertThatThrownBy(() -> convert("<patent-grant><version><version-no>2.0</version-no></version></patent-grant>"))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("not a Trademark Applications Daily");
        assertThatThrownBy(() -> convert("<trademark-applications-daily><version><version-date>20041108</version-date>"
                + "</version></trademark-applications-daily>"))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("no version-no");
    }

    @Test
    void aFileThatIsNotWellFormedIsRefusedWithoutItsContent() {
        String broken = file(caseFile("97000094", "Secretmark", "4000", "700", "025")).replace("</case-file-header>", "");

        assertThatThrownBy(() -> convert(broken)).isInstanceOf(UsptoConversionException.class)
                .hasMessageContaining("not well-formed").hasMessageContaining("line")
                .hasMessageNotContaining("Secretmark");
    }

    @Test
    void aFileThatSaysItHasNoDataGivesAnEmptyOutput() throws IOException {
        Converted c = convert("<?xml version=\"1.0\"?><trademark-applications-daily><version><version-no>2.0</version-no>"
                + "<version-date>20041108</version-date></version><application-information>"
                + "<data-available-code>N</data-available-code></application-information></trademark-applications-daily>");

        assertThat(c.output()).isEmpty();
        assertThat(c.report().dataAvailable()).isFalse();
        assertThat(c.report().recordsRead()).isZero();
    }

    @Test
    void aFileWithNoCaseFileGivesAnEmptyOutput() throws IOException {
        Converted c = convert(file());

        assertThat(c.output()).isEmpty();
        assertThat(c.report().dataAvailable()).isTrue();
    }

    // --- safety ---

    @Test
    void neverFetchesTheDtdOfTheDoctype() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(500);
            String doctype = "<!DOCTYPE trademark-applications-daily SYSTEM \"http://127.0.0.1:" + server.getLocalPort()
                    + "/tdxf.dtd\">\n";
            String xml = doctype + file(caseFile("97000101", "Zorvex", "4000", "700", "025"));

            Converted c = convert(xml.replaceFirst("<\\?xml[^>]*\\?>\\n", ""));

            assertThat(c.records()).hasSize(1);
            assertThatThrownBy(server::accept).as("no one asked for the DTD").isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void doesNotResolveAnExternalEntity() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRETTEXT");
        String doctype = "<!DOCTYPE trademark-applications-daily [<!ENTITY leak SYSTEM \"" + secret.toUri() + "\">]>\n";
        String xml = doctype + file(caseFile("97000102", "&leak;", "4000", "700", "025")).replace("&amp;leak;", "&leak;");

        String output;
        try {
            output = convert(xml.replaceFirst("<\\?xml[^>]*\\?>\\n", "")).output();
        } catch (UsptoConversionException e) {
            output = String.valueOf(e.getMessage());
        }

        assertThat(output).doesNotContain("TOPSECRETTEXT");
    }

    // --- bookkeeping ---

    @Test
    void theDigestsAndTheByteCountsCoverTheInputAndTheOutput() throws Exception {
        String xml = file(caseFile("97000111", "Zorvex", "4000", "700", "025"), caseFile("97000112", "Brask", "2000", "700", "025"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ConversionReport report = new UsptoDailyConverter(statusMap, normalizer, false)
                .convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), out);

        assertThat(report.inputBytes()).isEqualTo(xml.getBytes(StandardCharsets.UTF_8).length);
        assertThat(report.inputSha256()).isEqualTo(hash(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(report.outputBytes()).isEqualTo(out.size());
        assertThat(report.outputSha256()).isEqualTo(hash(out.toByteArray()));
    }

    @Test
    void theReportAsJsonHoldsTheCountsAndNoMark() throws IOException {
        Converted c = convert(file(caseFile("97000121", "Zorvex", "4000", "700", "025"),
                caseFile("97000122", "Brask", "2000", "700", "025")));

        String json = c.report().toJson();
        JsonNode tree = JSON.readTree(json);

        assertThat(tree.get("format").asText()).isEqualTo("listing-guard/uspto-convert-report/v1");
        assertThat(tree.get("recordsRead").asInt()).isEqualTo(2);
        assertThat(tree.get("recordsWritten").asInt()).isEqualTo(1);
        assertThat(tree.get("recordsDropped").asInt()).isEqualTo(1);
        assertThat(tree.get("dropped").get("logo_only").asInt()).isEqualTo(1);
        assertThat(tree.get("outputSha256").asText()).hasSize(64);
        assertThat(json).doesNotContain("Zorvex").doesNotContain("Brask").doesNotContain("97000121").endsWith("\n");
    }

    // --- the import reads the output ---

    @Test
    void theCatalogImportAcceptsTheOutput() throws Exception {
        Converted c = convert(file(
                caseFile("97000131", "Zorvex", "4000", "700", "025", "035"),
                caseFile("97000132", "Quill & Thorn", "5R12", "686", "009"),
                caseFile("97000133", "Old Brand", "1000", "900", "025")));
        Path raw = dir.resolve("raw");
        Files.createDirectories(raw);
        Files.writeString(raw.resolve("uspto.jsonl"), c.output());
        Set<Integer> allClasses = IntStream.rangeClosed(1, 45).boxed().collect(Collectors.toSet());
        CatalogManifest.Entry entry = CatalogManifest.Entry.single("uspto-test", "uspto.jsonl",
                CatalogImport.FORMAT_VERSION, "Synthetic USPTO-shaped file", "https://example.invalid/uspto",
                "test only", LocalDate.of(2026, 11, 5), c.report().outputSha256(), allClasses,
                Set.of(MarkStatus.values()));

        List<CatalogImport.CatalogRecord> records = new ArrayList<>();
        CatalogImport.ScanResult result = CatalogImport.scan(entry, raw, normalizer, records::add);

        assertThat(result.recordsRead()).isEqualTo(3);
        assertThat(records).extracting(CatalogImport.CatalogRecord::display)
                .containsExactly("Zorvex", "Quill & Thorn", "Old Brand");
        assertThat(records).extracting(CatalogImport.CatalogRecord::status)
                .containsExactly(MarkStatus.REGISTERED, MarkStatus.PENDING, MarkStatus.EXPIRED);
        assertThat(records.get(0).classes()).containsExactlyInAnyOrder(25, 35);
        assertThat(records.get(0).sourceId()).isEqualTo("97000131");
    }

    // --- a big file, a small heap ---

    private static final int RECORDS = 100_000;
    private static final int HEAP_MEGABYTES = 32;

    @Test
    void aFileLargerThanTheHeapIsConvertedWithoutLoadingIt() throws Exception {
        Path xml = dir.resolve("big.xml");
        String filler = "Clothing for adults and children, lorem ipsum dolor sit amet, ".repeat(6);
        try (BufferedWriter out = Files.newBufferedWriter(xml, StandardCharsets.UTF_8)) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trademark-applications-daily><version>"
                    + "<version-no>2.0</version-no><version-date>20041108</version-date></version>"
                    + "<application-information><file-segments><file-segment>TRMK</file-segment><action-keys>");
            for (int i = 0; i < RECORDS; i++) {
                String status = i % 5 == 0 ? "973" : "700";
                String drawing = i % 7 == 0 ? "2000" : "4000";
                out.write(caseFileWith(String.format("97%06d", i), "Brand " + letters(i), drawing, status,
                        "<classifications><classification><international-code>025</international-code></classification>"
                                + "</classifications>").replace("<text>Clothing</text>", "<text>" + filler + "</text>"));
            }
            out.write("</action-keys></file-segments></application-information></trademark-applications-daily>");
        }
        assertThat(Files.size(xml)).as("the file must not fit in the heap of the child JVM")
                .isGreaterThan(HEAP_MEGABYTES * 1024L * 1024L);

        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path jsonl = dir.resolve("big.jsonl");
        Process process = new ProcessBuilder(java, "-Xmx" + HEAP_MEGABYTES + "m", "-cp", classPath,
                UsptoConvertMain.class.getName(), xml.toString(), jsonl.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        // i % 5 == 0 has status 973 (dropped); i % 7 == 0 is a logo (dropped, and counted before the status)
        long expectedWritten = IntStream.range(0, RECORDS).filter(i -> i % 5 != 0 && i % 7 != 0).count();
        assertThat(output.trim()).isEqualTo("read=" + RECORDS + " written=" + expectedWritten);
        try (var lines = Files.lines(jsonl)) {
            assertThat(lines.count()).isEqualTo(expectedWritten);
        }
    }

    /** Distinct letters for a counter: normalization keeps them, so the marks stay distinct. */
    private static String letters(int n) {
        StringBuilder text = new StringBuilder();
        int rest = n;
        do {
            text.append((char) ('a' + rest % 26));
            rest /= 26;
        } while (rest > 0);
        return text.toString();
    }
}
