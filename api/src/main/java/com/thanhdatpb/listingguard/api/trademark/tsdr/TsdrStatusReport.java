package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.CallResult;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.MarkReport;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.StatusClass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The report of the {@code tsdr-status} job (ADR-009, "TSDR status check"): which marks that recent findings named are dead
 * in the TSDR, and which marks it proposes to leave out of the catalog at the next sync. It only proposes: nothing here edits a
 * catalog, activates a version or changes a finding. It holds marks of the public catalog, serial numbers, USPTO statuses and
 * counts; never listing text, a client, a check id or an input hash.
 */
final class TsdrStatusReport {

    /** What the report is made of. */
    record Data(Instant generatedAt, Long runId, TsdrStatusStore.Mode mode, String catalogVersion, Instant matchedFrom,
                Instant matchedTo, String statusMapVersion, int selected, int recorded, int failed, boolean stopped,
                List<MarkReport> matchedMarks, List<String> notes, TsdrProperties settings) {
    }

    static final String PROPOSAL = "REMOVE_AT_NEXT_SYNC";

    private static final ObjectMapper JSON = new ObjectMapper();

    private TsdrStatusReport() {
    }

    /** Writes {@code report.json} and {@code dead-marks.csv} into {@code dir} (created) and returns it. */
    static Path write(Path dir, Data data) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json(data)) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("dead-marks.csv"), csv(dead(data.matchedMarks())), StandardCharsets.UTF_8);
        return dir;
    }

    static List<MarkReport> dead(List<MarkReport> marks) {
        return marks.stream().filter(m -> m.latestOk() != null && m.latestOk().statusClass() == StatusClass.DEAD).toList();
    }

    private static Map<String, Object> json(Data data) {
        List<MarkReport> marks = data.matchedMarks();
        List<MarkReport> unknown = marks.stream()
                .filter(m -> m.latestOk() != null && m.latestOk().statusClass() == StatusClass.UNKNOWN).toList();
        List<MarkReport> notFound = marks.stream()
                .filter(m -> m.latestOk() == null && m.lastResult() == CallResult.NOT_FOUND).toList();
        long never = marks.stream().filter(m -> m.lastResult() == null).count();
        long live = marks.stream().filter(m -> m.latestOk() != null && m.latestOk().statusClass() == StatusClass.LIVE).count();
        long failedLast = marks.stream().filter(m -> m.latestOk() == null && m.lastResult() != null
                && m.lastResult() != CallResult.NOT_FOUND).count();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", data.generatedAt().toString());
        root.put("runId", data.runId());
        root.put("mode", data.mode().name());
        root.put("catalogVersion", data.catalogVersion());
        root.put("statusMapVersion", data.statusMapVersion());
        root.put("matchWindow", Map.of("from", data.matchedFrom().toString(), "to", data.matchedTo().toString()));
        root.put("run", Map.of("selected", data.selected(), "recorded", data.recorded(), "failed", data.failed(),
                "stoppedEarly", data.stopped()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("matchedMarks", marks.size());
        counts.put("live", live);
        counts.put("dead", dead(marks).size());
        counts.put("unknownStatus", unknown.size());
        counts.put("notFound", notFound.size());
        counts.put("lastTryFailed", failedLast);
        counts.put("neverChecked", never);
        root.put("counts", counts);
        root.put("proposedExclusions", dead(marks).stream().map(m -> mark(m, true)).toList());
        root.put("unknownStatus", unknown.stream().map(m -> mark(m, false)).toList());
        root.put("notFound", notFound.stream().map(m -> mark(m, false)).toList());
        root.put("notes", data.notes());
        root.put("settings", settings(data.settings()));
        root.put("unverified", List.of(
                "The key header name, the element or field names that hold the status, and the answer for an unknown serial "
                        + "number are not verified against an official page (ADR-009, TSDR status check).",
                "A status class comes from the USPTO status map " + data.statusMapVersion() + ", which is the maintainer's "
                        + "reading of the public status-code table and is unverified; check each proposed exclusion by hand.",
                "A proposal is not a legal conclusion and nothing was removed: the catalog is immutable and only the next "
                        + "sync, after you decide, can leave a mark out."));
        return root;
    }

    private static Map<String, Object> mark(MarkReport m, boolean proposal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", m.sourceId());
        out.put("serialNumber", m.serial());
        out.put("mark", m.display());
        out.put("catalogStatus", m.catalogStatus());
        out.put("matches", m.matches());
        if (m.latestOk() != null) {
            out.put("statusCode", m.latestOk().statusCode());
            out.put("statusDescription", m.latestOk().statusDescription());
            out.put("statusDate", m.latestOk().statusDate() == null ? null : m.latestOk().statusDate().toString());
            out.put("statusClass", m.latestOk().statusClass().name());
            out.put("checkedAt", m.latestOk().checkedAt().toString());
        } else if (m.lastCheckedAt() != null) {
            out.put("lastResult", m.lastResult().name());
            out.put("checkedAt", m.lastCheckedAt().toString());
        }
        if (proposal) {
            out.put("proposal", PROPOSAL);
            out.put("note", "Nhãn này vẫn được khớp trong các kết quả gần đây nhưng TSDR báo đã hết hiệu lực. "
                    + "Đề xuất loại khỏi catalog ở lần sync sau; chưa loại gì cả. Xem lại bằng tay trước khi quyết định.");
        }
        return out;
    }

    private static Map<String, Object> settings(TsdrProperties p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxMarksPerRun", p.maxMarksPerRun());
        out.put("unmatchedPerRun", p.unmatchedPerRun());
        out.put("matchWindow", p.matchWindow().toString());
        out.put("recheckInterval", p.recheckInterval().toString());
        out.put("errorRetryInterval", p.errorRetryInterval().toString());
        return out;
    }

    private static String csv(List<MarkReport> dead) {
        StringBuilder out = new StringBuilder(
                "serial_number,mark,catalog_status,matches,tsdr_status_code,tsdr_status_description,tsdr_status_date,"
                        + "tsdr_checked_at,proposal\r\n");
        for (MarkReport m : dead) {
            List<String> cells = new ArrayList<>(List.of(m.serial(), m.display(), m.catalogStatus(), String.valueOf(m.matches()),
                    m.latestOk().statusCode(), nullToEmpty(m.latestOk().statusDescription()),
                    m.latestOk().statusDate() == null ? "" : m.latestOk().statusDate().toString(),
                    m.latestOk().checkedAt().toString(), PROPOSAL));
            out.append(String.join(",", cells.stream().map(TsdrStatusReport::cell).toList())).append("\r\n");
        }
        return out.toString();
    }

    /** A CSV cell: quoted, and a leading formula character is defused so a spreadsheet does not run a mark's text. */
    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Deletes the oldest report folders so {@code keep} remain; only folders named like a report's timestamp are touched. */
    static void prune(Path outDir, int keep) throws IOException {
        if (!Files.isDirectory(outDir)) {
            return;
        }
        List<Path> reports;
        try (Stream<Path> list = Files.list(outDir)) {
            reports = list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("[0-9]{8}T[0-9]{6}Z"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        for (Path old : reports.stream().skip(keep).toList()) {
            try (Stream<Path> walk = Files.walk(old)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
