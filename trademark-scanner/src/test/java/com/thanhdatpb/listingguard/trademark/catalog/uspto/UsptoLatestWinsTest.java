package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.caseFile;
import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.caseFileOn;
import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.file;
import static com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoTestXml.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The latest transaction of a serial number wins, and the filters of the converter (ADR-009, PR A2c). Every file is
 * small synthetic XML that follows the public DTD V2.0, the marks and serial numbers are invented, and nothing was read
 * from the network. The filters are the shipped ones unless a test says otherwise.
 */
class UsptoLatestWinsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UsptoStatusMap statusMap = UsptoTestXml.statusMap();
    private final UsptoConvertFilters shipped = UsptoConvertFilters.loadClasspath(
            "uspto/uspto-convert-filters-2026-11.yaml");
    private final TextNormalizer normalizer = new TextNormalizer(TestRulesets.shipped().normalization());

    private record Converted(ConversionReport report, String output) {
        List<JsonNode> records() throws IOException {
            List<JsonNode> records = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (!line.isEmpty()) {
                    records.add(JSON.readTree(line));
                }
            }
            return records;
        }

        List<String> serials() throws IOException {
            return records().stream().map(r -> r.get("source_id").asText()).toList();
        }
    }

    private Converted convert(UsptoConvertFilters filters, UsptoCatalogConverter.Options options,
                              List<UsptoCatalogConverter.Source> sources) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConversionReport report = new UsptoCatalogConverter(statusMap, filters, normalizer, options)
                .convert(sources, out);
        return new Converted(report, out.toString(StandardCharsets.UTF_8));
    }

    private Converted convert(UsptoCatalogConverter.Source... sources) throws IOException {
        return convert(shipped, UsptoCatalogConverter.Options.defaults(), List.of(sources));
    }

    /** Three days of changes to five serial numbers. */
    private List<UsptoCatalogConverter.Source> threeDays() {
        return List.of(
                source("apc241101.xml", file(
                        caseFileOn("97000001", "20241101", "Zorvex", "4000", "686", "025"),   // pending
                        caseFileOn("97000002", "20241101", "Brask", "4000", "700", "025"),    // registered
                        caseFileOn("97000003", "20241101", "Quill", "4000", "700", "025"),    // registered
                        caseFileOn("97000004", "20241101", "Marlow", "4000", "710", "025"),   // cancelled
                        caseFileOn("97000005", "20241101", "Tessel", "4000", "700", "025"))), // only here
                source("apc241102.xml", file(
                        caseFileOn("97000001", "20241102", "Zorvex", "4000", "700", "025"),   // registered
                        caseFileOn("97000002", "20241102", "Brask", "4000", "710", "025"),    // live, then dead
                        caseFileOn("97000004", "20241102", "Marlow", "4000", "700", "025"))), // dead, then live again
                source("apc241103.xml", file(
                        caseFileOn("97000001", "20241103", "Zorvex Pro", "4000", "700", "025"), // the mark changed
                        caseFileOn("97000003", "20241103", "Quill", "4000", "900", "025"))));   // expired, a day skipped
    }

    // --- the result, whatever the order of the files ---

    @Test
    void theLatestRecordOfEachSerialNumberWinsAndALatestDeadOneLeavesTheCatalog() throws IOException {
        Converted c = convert(shipped, UsptoCatalogConverter.Options.defaults(), threeDays());

        // written in the order of the files, then of the records: 5 (day 1), 4 (day 2), 1 (day 3)
        assertThat(c.output()).isEqualTo(String.join("",
                "{\"mark\":\"Tessel\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000005\"}\n",
                "{\"mark\":\"Marlow\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000004\"}\n",
                "{\"mark\":\"Zorvex Pro\",\"status\":\"REGISTERED\",\"nice_classes\":[25],\"source_id\":\"97000001\"}\n"));
        ConversionReport r = c.report();
        assertThat(r.recordsRead()).isEqualTo(10);
        assertThat(r.recordsWritten()).isEqualTo(3);
        assertThat(r.superseded()).isEqualTo(5);
        assertThat(r.deadRemoved()).isEqualTo(2);
        assertThat(r.deadByStatus()).containsOnly(Map.entry("710", 1L), Map.entry("900", 1L));
        assertThat(r.distinctSerials()).isEqualTo(5);
        assertThat(r.filteredTotal()).isZero();
        assertThat(r.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(r.recordsRead()).isEqualTo(
                r.recordsWritten() + r.recordErrors() + r.superseded() + r.deadRemoved() + r.filteredTotal());
    }

    @Test
    void theSameResultInEveryOrderOfThreeFiles() throws IOException {
        List<UsptoCatalogConverter.Source> sources = threeDays();
        Converted reference = convert(shipped, UsptoCatalogConverter.Options.defaults(), sources);
        int orders = 0;

        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                for (int c = 0; c < 3; c++) {
                    if (a == b || b == c || a == c) {
                        continue;
                    }
                    Converted again = convert(shipped, UsptoCatalogConverter.Options.defaults(),
                            List.of(sources.get(a), sources.get(b), sources.get(c)));
                    assertThat(again.output()).as("order %d %d %d", a, b, c).isEqualTo(reference.output());
                    assertThat(again.report().toJson()).as("order %d %d %d", a, b, c)
                            .isEqualTo(reference.report().toJson());
                    orders++;
                }
            }
        }

        assertThat(orders).isEqualTo(6);
    }

    @Test
    void theModelAgreesOnRandomFilesInEveryOrder() throws IOException {
        // an oracle: sort every record by the documented key and keep the last of each serial number
        String[] statuses = {"700", "686", "710", "900", "973", "999"};
        Random random = new Random(20241101L);
        List<List<Row>> rows = new ArrayList<>();
        List<String> dates = List.of("20241101", "20241102", "20241102", "20241103");
        for (int file = 0; file < 4; file++) {
            List<Row> fileRows = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                fileRows.add(new Row(String.format("970%05d", random.nextInt(60)), dates.get(random.nextInt(dates.size())),
                        statuses[random.nextInt(statuses.length)], file, i));
            }
            rows.add(fileRows);
        }
        // the file name dates are the opposite of the order of the names, to show the date comes first
        String[] names = {"apc241104-d.xml", "apc241103-c.xml", "apc241102-b.xml", "apc241102-a.xml"};
        List<UsptoCatalogConverter.Source> sources = new ArrayList<>();
        for (int file = 0; file < 4; file++) {
            String[] caseFiles = rows.get(file).stream()
                    .map(r -> caseFileOn(r.serial(), r.date(), "Mark" + r.serial(), "4000", r.status(), "025"))
                    .toArray(String[]::new);
            sources.add(source(names[file], file(caseFiles)));
        }

        // expected: the order of the files is by name date, then name: 241102-a, 241102-b, 241103-c, 241104-d
        int[] rank = {3, 2, 1, 0};
        Map<String, Row> latest = new TreeMap<>();
        rows.stream().flatMap(List::stream).filter(r -> !r.status().equals("973") && !r.status().equals("999"))
                .forEach(r -> latest.merge(r.serial(), r, (x, y) -> later(x, y, rank) ? x : y));
        Map<String, String> expected = new TreeMap<>();
        latest.values().stream().filter(r -> r.status().equals("700") || r.status().equals("686"))
                .forEach(r -> expected.put(r.serial(), r.status().equals("700") ? "REGISTERED" : "PENDING"));
        assertThat(expected).as("the data has some of each").hasSizeBetween(5, 59);

        Random shuffle = new Random(7L);
        for (int round = 0; round < 24; round++) {
            List<UsptoCatalogConverter.Source> given = new ArrayList<>(sources);
            Collections.shuffle(given, shuffle);
            Converted c = convert(shipped, UsptoCatalogConverter.Options.defaults(), given);

            Map<String, String> actual = new TreeMap<>();
            for (JsonNode record : c.records()) {
                assertThat(actual.put(record.get("source_id").asText(), record.get("status").asText()))
                        .as("one record per serial number").isNull();
            }
            assertThat(actual).isEqualTo(expected);
        }
    }

    /** A record of the random data: where it is and what it says. */
    private record Row(String serial, String date, String status, int file, int position) {
    }

    /** True when {@code x} is later than {@code y}: the transaction date, then the file's rank, then the position. */
    private static boolean later(Row x, Row y, int[] rank) {
        int byDate = x.date().compareTo(y.date());
        if (byDate != 0) {
            return byDate > 0;
        }
        if (rank[x.file()] != rank[y.file()]) {
            return rank[x.file()] > rank[y.file()];
        }
        return x.position() > y.position();
    }

    // --- live, dead, live again ---

    @Test
    void aMarkThatWasLiveAndIsDeadInItsLatestRecordIsLeftOut() throws IOException {
        Converted c = convert(
                source("apc241101.xml", file(caseFileOn("97000011", "20241101", "Zorvex", "4000", "700", "025"))),
                source("apc241102.xml", file(caseFileOn("97000011", "20241102", "Zorvex", "4000", "710", "025"))));

        assertThat(c.output()).isEmpty();
        assertThat(c.report().superseded()).isEqualTo(1);
        assertThat(c.report().deadRemoved()).isEqualTo(1);
    }

    @Test
    void aMarkThatWasDeadAndIsLiveAgainIsWrittenWithTheLaterRecordsData() throws IOException {
        Converted c = convert(
                source("apc241101.xml", file(caseFileOn("97000012", "20241101", "Zorvex", "4000", "710", "025"))),
                source("apc241102.xml", file(caseFileOn("97000012", "20241102", "Zorvex Again", "4000", "686", "025", "009"))));

        assertThat(c.records()).hasSize(1);
        assertThat(c.records().get(0).get("mark").asText()).isEqualTo("Zorvex Again");
        assertThat(c.records().get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(c.records().get(0).get("nice_classes")).extracting(JsonNode::asInt).containsExactly(9, 25);
        assertThat(c.report().deadRemoved()).isZero();
    }

    @Test
    void aLaterRecordThatTheFiltersWouldDropStillReplacesTheEarlierOnes() throws IOException {
        // a logo (no text) as the latest record, and a record whose classes are no longer listed
        Converted logo = convert(
                source("apc241101.xml", file(caseFileOn("97000013", "20241101", "Zorvex", "4000", "700", "025"))),
                source("apc241102.xml", file(caseFileOn("97000013", "20241102", "Zorvex", "2000", "700", "025"))));
        Converted otherClass = convert(
                source("apc241101.xml", file(caseFileOn("97000014", "20241101", "Zorvex", "4000", "700", "025"))),
                source("apc241102.xml", file(caseFileOn("97000014", "20241102", "Zorvex", "4000", "700", "035"))));

        assertThat(logo.output()).isEmpty();
        assertThat(logo.report().filtered()).containsOnly(Map.entry(UsptoCatalogConverter.LOGO_ONLY, 1L));
        assertThat(logo.report().superseded()).isEqualTo(1);
        assertThat(otherClass.output()).isEmpty();
        assertThat(otherClass.report().filtered()).containsOnly(Map.entry(UsptoCatalogConverter.CLASS_NOT_INCLUDED, 1L));
    }

    @Test
    void aRecordThatCarriesNoStatusOrCannotBePlacedDoesNotReplaceAnEarlierOne() throws IOException {
        Converted c = convert(
                source("apc241101.xml", file(caseFileOn("97000015", "20241101", "Zorvex", "4000", "700", "025"))),
                source("apc241102.xml", file(
                        caseFileOn("97000015", "20241102", "Zorvex", "4000", "973", "025"),    // no status of the mark
                        caseFileOn("97000015", "20241102", "Zorvex", "4000", "999", "025"),    // not in the map
                        caseFileOn("97000015", "20241102", "Zorvex", "4000", null, "025"),     // no status
                        caseFileOn("97000015", null, "Zorvex", "4000", "710", "025"))));       // cannot be placed

        assertThat(c.serials()).containsExactly("97000015");
        assertThat(c.report().superseded()).isZero();
        assertThat(c.report().filtered()).containsOnly(Map.entry("status_dropped:973", 1L),
                Map.entry("status_unmapped:999", 1L), Map.entry("status_unmapped:missing", 1L));
        assertThat(c.report().errors()).containsOnly(Map.entry(UsptoCatalogConverter.MISSING_TRANSACTION_DATE, 1L));
        assertThat(c.report().completeness()).isEqualTo(Completeness.INCOMPLETE);
    }

    // --- the same transaction date ---

    @Test
    void theTransactionDateComesBeforeTheDateOfTheFileName() throws IOException {
        // the file named for the later day holds the earlier transaction: it loses
        Converted c = convert(
                source("apc241102.xml", file(caseFileOn("97000021", "20241101", "Old", "4000", "700", "025"))),
                source("apc241101.xml", file(caseFileOn("97000021", "20241102", "New", "4000", "700", "025"))));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("New");
    }

    @Test
    void withTheSameTransactionDateTheLaterFileNameDateWins() throws IOException {
        List<UsptoCatalogConverter.Source> sources = List.of(
                source("apc241102.xml", file(caseFileOn("97000022", "20241101", "Later file", "4000", "700", "025"))),
                source("apc241101.xml", file(caseFileOn("97000022", "20241101", "Earlier file", "4000", "700", "025"))));

        assertThat(convert(shipped, UsptoCatalogConverter.Options.defaults(), sources).records())
                .extracting(r -> r.get("mark").asText()).containsExactly("Later file");
        assertThat(convert(shipped, UsptoCatalogConverter.Options.defaults(), List.of(sources.get(1), sources.get(0)))
                .records()).extracting(r -> r.get("mark").asText()).containsExactly("Later file");
    }

    @Test
    void withTheSameFileNameDateTheLaterFileNameWins() throws IOException {
        Converted c = convert(
                source("apc241101-b.xml", file(caseFileOn("97000023", "20241101", "File b", "4000", "700", "025"))),
                source("apc241101-a.xml", file(caseFileOn("97000023", "20241101", "File a", "4000", "700", "025"))));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("File b");
    }

    @Test
    void aFileNameWithNoDateLosesATieToADatedOne() throws IOException {
        Converted c = convert(
                source("zzz.xml", file(caseFileOn("97000024", "20241101", "Undated", "4000", "700", "025"))),
                source("apc241101.xml", file(caseFileOn("97000024", "20241101", "Dated", "4000", "700", "025"))));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("Dated");
    }

    @Test
    void inOneFileTheLaterRecordWinsWhateverItsTransactionDateTie() throws IOException {
        Converted c = convert(source("apc241101.xml", file(
                caseFileOn("97000025", "20241101", "First", "4000", "700", "025"),
                caseFileOn("97000025", "20241101", "Second", "4000", "710", "025"),
                caseFileOn("97000026", "20241101", "Third", "4000", "710", "025"),
                caseFileOn("97000026", "20241101", "Fourth", "4000", "700", "025"))));

        assertThat(c.records()).extracting(r -> r.get("mark").asText()).containsExactly("Fourth");
        assertThat(c.report().superseded()).isEqualTo(2);
        assertThat(c.report().deadRemoved()).isEqualTo(1);
    }

    // --- the filters ---

    @Test
    void keepsARecordWithAtLeastOneListedClassAndWritesAllItsClasses() throws IOException {
        Converted c = convert(source("apc241101.xml", file(
                caseFileOn("97000031", "20241101", "Only35", "4000", "700", "035"),
                caseFileOn("97000032", "20241101", "Both", "4000", "700", "035", "025"),
                caseFileOn("97000033", "20241101", "Class26", "4000", "700", "026"),
                caseFileOn("97000034", "20241101", "Class46", "4000", "700", "046"))));

        assertThat(c.serials()).containsExactly("97000032", "97000033");
        assertThat(c.records().get(0).get("nice_classes")).extracting(JsonNode::asInt).containsExactly(25, 35);
        assertThat(c.report().filtered()).containsOnly(Map.entry(UsptoCatalogConverter.CLASS_NOT_INCLUDED, 1L),
                Map.entry(UsptoCatalogConverter.NO_VALID_NICE_CLASS, 1L));
    }

    @Test
    void theClassesComeFromTheFilters() throws IOException {
        UsptoConvertFilters only35 = UsptoTestXml.filters(List.of(35), shipped.liveStatusCodes(), true);

        Converted c = convert(only35, UsptoCatalogConverter.Options.defaults(), List.of(source("apc241101.xml", file(
                caseFileOn("97000035", "20241101", "Retail", "4000", "700", "035"),
                caseFileOn("97000036", "20241101", "Clothes", "4000", "700", "025")))));

        assertThat(c.serials()).containsExactly("97000035");
    }

    @Test
    void keepsOnlyTheLiveStatusCodesAndCountsTheDeadOnesByCode() throws IOException {
        Converted c = convert(source("apc241101.xml", file(
                caseFileOn("97000041", "20241101", "Registered", "4000", "700", "025"),
                caseFileOn("97000042", "20241101", "Pending", "4000", "686", "025"),
                caseFileOn("97000043", "20241101", "Expired", "4000", "900", "025"),
                caseFileOn("97000044", "20241101", "Cancelled", "4000", "710", "025"),
                caseFileOn("97000045", "20241101", "Abandoned", "4000", "601", "025"),
                caseFileOn("97000046", "20241101", "Abandoned too", "4000", "601", "025"))));

        assertThat(c.serials()).containsExactly("97000041", "97000042");
        assertThat(c.report().deadRemoved()).isEqualTo(4);
        assertThat(c.report().deadByStatus()).containsOnly(Map.entry("900", 1L), Map.entry("710", 1L),
                Map.entry("601", 2L));
        assertThat(c.report().superseded()).isZero();
    }

    @Test
    void theLiveCodesComeFromTheFilters() throws IOException {
        UsptoConvertFilters withExpired = UsptoTestXml.filters(List.of(25), List.of("700", "900"), true);

        Converted c = convert(withExpired, UsptoCatalogConverter.Options.defaults(), List.of(source("apc241101.xml", file(
                caseFileOn("97000047", "20241101", "Registered", "4000", "700", "025"),
                caseFileOn("97000048", "20241101", "Expired", "4000", "900", "025"),
                caseFileOn("97000049", "20241101", "Pending", "4000", "686", "025")))));

        assertThat(c.serials()).containsExactly("97000047", "97000048");
        assertThat(c.records().get(1).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(c.report().deadByStatus()).containsOnly(Map.entry("686", 1L));
    }

    @Test
    void dropsAMarkWithNoWordsOnlyWhenTheFiltersAskForText() throws IOException {
        List<UsptoCatalogConverter.Source> sources = List.of(source("apc241101.xml", file(
                caseFileOn("97000051", "20241101", "Logo with a name", "2000", "700", "025"),
                caseFileOn("97000052", "20241101", null, "2000", "700", "025"),
                caseFileOn("97000053", "20241101", "Plain words", "4000", "700", "025"))));

        Converted textOnly = convert(shipped, UsptoCatalogConverter.Options.defaults(), sources);
        Converted anything = convert(UsptoTestXml.filters(List.of(25), shipped.liveStatusCodes(), false),
                UsptoCatalogConverter.Options.defaults(), sources);

        assertThat(textOnly.serials()).containsExactly("97000053");
        assertThat(textOnly.report().filtered()).containsOnly(Map.entry(UsptoCatalogConverter.LOGO_ONLY, 2L));
        assertThat(anything.serials()).containsExactly("97000051", "97000053");
        assertThat(anything.report().filtered()).containsOnly(Map.entry(UsptoCatalogConverter.BLANK_MARK, 1L));
    }

    // --- bad files and strict ---

    private static final String BROKEN_TAIL = "<case-file><serial-number>97000061</serial-number><case-file-header>";

    private UsptoCatalogConverter.Source broken(String name, String transactionDate) {
        // a record that would replace the live one, then the XML breaks
        String xml = file(caseFileOn("97000061", transactionDate, "Zorvex", "4000", "710", "025"))
                .replace("</action-keys>", BROKEN_TAIL + "</action-keys>");
        return source(name, xml);
    }

    @Test
    void aBrokenFileIsLeftOutWholeAndTheOthersAreConvertedAndTheRunIsIncomplete() throws IOException {
        Converted c = convert(
                source("apc241101.xml", file(caseFileOn("97000061", "20241101", "Zorvex", "4000", "700", "025"))),
                broken("apc241102.xml", "20241102"),
                source("apc241103.xml", file(caseFileOn("97000062", "20241103", "Brask", "4000", "700", "025"))));

        assertThat(c.serials()).as("the dead record of the broken file did not count").containsExactly("97000061",
                "97000062");
        ConversionReport r = c.report();
        assertThat(r.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(r.filesFailed()).isEqualTo(1);
        assertThat(r.files()).extracting(ConversionReport.FileReport::status).containsExactly(
                ConversionReport.FileStatus.OK, ConversionReport.FileStatus.FAILED, ConversionReport.FileStatus.OK);
        ConversionReport.FileReport failed = r.files().get(1);
        assertThat(failed.name()).isEqualTo("apc241102.xml");
        assertThat(failed.error()).startsWith("apc241102.xml: ").contains("not well-formed").contains("line")
                .contains("column").doesNotContain("Zorvex");
        assertThat(r.recordsRead()).as("only the files that were read").isEqualTo(2);
        assertThat(r.toJson()).contains("\"completeness\" : \"INCOMPLETE\"").contains("\"filesFailed\" : 1");
    }

    @Test
    void strictStopsAtABrokenFileAndNamesItAndTheLine() {
        assertThatThrownBy(() -> convert(shipped, new UsptoCatalogConverter.Options(false, true, 1000), List.of(
                source("apc241101.xml", file(caseFileOn("97000061", "20241101", "Zorvex", "4000", "700", "025"))),
                broken("apc241102.xml", "20241102"))))
                .isInstanceOf(UsptoConversionException.class)
                .hasMessageStartingWith("apc241102.xml: ").hasMessageContaining("not well-formed")
                .hasMessageContaining("line").hasMessageNotContaining("Zorvex");
    }

    @Test
    void aRecordErrorInTheSecondFileNamesThatFileAndTheRecord() throws IOException {
        Converted c = convert(
                source("apc241101.xml", file(caseFileOn("97000063", "20241101", "Zorvex", "4000", "700", "025"))),
                source("apc241102.xml", file(
                        caseFileOn("97000064", "20241102", "Brask", "4000", "700", "025"),
                        caseFileOn("97000065", "2024110", "Quill", "4000", "700", "025"))));

        assertThat(c.serials()).containsExactly("97000063", "97000064");
        assertThat(c.report().errorDetails()).hasSize(1);
        ConversionReport.RecordError error = c.report().errorDetails().get(0);
        assertThat(error.file()).isEqualTo("apc241102.xml");
        assertThat(error.record()).isEqualTo(2);
        assertThat(error.code()).isEqualTo(UsptoCatalogConverter.INVALID_TRANSACTION_DATE);
        assertThat(error.line()).isPositive();
    }

    @Test
    void whenNoFileCanBeReadTheRunFailsAndWritesNothingUseful() {
        assertThatThrownBy(() -> convert(source("apc241101.xml", "not xml at all"),
                source("apc241102.xml", "<patent-grant/>")))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("No file could be read")
                .hasMessageContaining("apc241101.xml").hasMessageContaining("apc241102.xml");
    }

    // --- limits and checks of the input ---

    @Test
    void refusesNoFileTwoFilesWithOneNameAndTooManyFiles() {
        assertThatThrownBy(() -> convert()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no file");
        assertThatThrownBy(() -> convert(source("a.xml", file()), source("a.xml", file())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a.xml").hasMessageContaining("twice");
        List<UsptoCatalogConverter.Source> many = IntStream.rangeClosed(0, UsptoCatalogConverter.MAX_FILES)
                .mapToObj(i -> source("f" + i + ".xml", file())).toList();
        assertThatThrownBy(() -> convert(shipped, UsptoCatalogConverter.Options.defaults(), many))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(String.valueOf(UsptoCatalogConverter.MAX_FILES));
    }

    @Test
    void stopsWithAClearMessageWhenThereAreMoreDistinctSerialNumbersThanTheLimit() {
        assertThatThrownBy(() -> convert(shipped, new UsptoCatalogConverter.Options(false, false, 2), List.of(
                source("apc241101.xml", file(
                        caseFileOn("97000071", "20241101", "Aaa", "4000", "700", "025"),
                        caseFileOn("97000072", "20241101", "Bbb", "4000", "700", "025"),
                        caseFileOn("97000073", "20241101", "Ccc", "4000", "700", "025"))))))
                .isInstanceOf(UsptoConversionException.class).hasMessageContaining("max-distinct-serials");
    }

    @Test
    void stopsWhenAFileChangesBetweenThePasses() {
        AtomicInteger opens = new AtomicInteger();
        String first = file(caseFileOn("97000081", "20241101", "Zorvex", "4000", "700", "025"));
        String second = file(caseFileOn("97000081", "20241101", "Zorvex", "4000", "710", "025"));
        UsptoCatalogConverter.Source changing = new UsptoCatalogConverter.Source() {
            @Override
            public String name() {
                return "apc241101.xml";
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream((opens.getAndIncrement() == 0 ? first : second)
                        .getBytes(StandardCharsets.UTF_8));
            }
        };

        assertThatThrownBy(() -> convert(changing)).isInstanceOf(UsptoConversionException.class)
                .hasMessageContaining("apc241101.xml").hasMessageContaining("changed while it was converted");
    }

    @Test
    void theSerialIndexKeepsTheLargestKeyAndGrowsAndHonoursItsLimit() {
        SerialIndex index = new SerialIndex(5_000);
        for (int serial = 0; serial < 5_000; serial++) {
            index.offer(serial * 7 + 1, serial + 1L);
        }
        index.offer(1, 10_000L);
        index.offer(1, 3L);

        assertThat(index.size()).isEqualTo(5_000);
        assertThat(index.get(1)).isEqualTo(10_000L);
        assertThat(index.get(7 * 4_999 + 1)).isEqualTo(5_000L);
        assertThat(index.get(2)).as("never offered").isZero();
        assertThatThrownBy(() -> index.offer(99_999_999, 1L)).isInstanceOf(UsptoConversionException.class);
        assertThatThrownBy(() -> index.offer(5, 0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
