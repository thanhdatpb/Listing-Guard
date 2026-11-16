package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thanhdatpb.listingguard.trademark.Completeness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one {@code hf-convert} run did (ADR-030; format {@code listing-guard/hf-convert-report/v1}).
 *
 * <p>Every serial number of the case files is counted exactly once, so
 * {@code distinctSerials == recordsWritten + recordErrors + deadRemoved + filteredTotal()}: a record error (see
 * {@code errors}), not live in its latest row ({@code deadRemoved}, by status code in {@code deadByStatus}), then the
 * first filter that drops it ({@code filtered}). A report holds counts, file names and versions, never a mark or a serial
 * number.
 *
 * @param completeness  {@code INCOMPLETE} when {@code problems} or {@code recordErrors} is not empty: the output cannot be
 *                      trusted for a deployment
 * @param source        the four data-file keys to copy into {@code catalogs/manifest.yaml}
 * @param dataset       what {@code profile/build_lineage.json} says, or null when the input has none
 * @param caseFileRows  rows of every case_file file read (more than {@code distinctSerials} when files of two builds
 *                      were read: the latest row of a serial number wins)
 * @param lookup        the check of the filters' status codes against {@code lookups/status_codes.csv}
 * @param outputSha256  SHA-256 of the output: the {@code sha256} to put in {@code catalogs/manifest.yaml}
 */
public record HfConversionReport(
        Completeness completeness,
        boolean strict,
        String filtersVersion,
        String filtersHash,
        String rulesetVersion,
        Integer minYear,
        String input,
        boolean remote,
        Source source,
        Lineage dataset,
        List<FileReport> files,
        long caseFileRows,
        long intlClassRows,
        long duplicateCaseFileRows,
        long distinctSerials,
        long recordsWritten,
        long recordErrors,
        long deadRemoved,
        long ignoredClassCodes,
        Map<String, Long> filtered,
        Map<String, Long> errors,
        Map<String, Long> deadByStatus,
        LookupCheck lookup,
        List<String> problems,
        String duckdbVersion,
        long elapsedMillis,
        long outputBytes,
        String outputSha256) {

    public static final String FORMAT = "listing-guard/hf-convert-report/v1";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The data-file keys of a manifest entry. */
    public record Source(String sourceName, String url, String license, String sourceDate) {
    }

    /** What the dataset says about its own build. Any member may be null when the file does not hold it. */
    public record Lineage(String buildId, String builtAt, String dataCurrentThrough, String annualCutoff,
                          Long caseFileRows, Long intlClassRows) {
    }

    /** One Parquet file read; {@code bytes} and {@code sha256} are null for a remote file. */
    public record FileReport(String table, String name, Long bytes, String sha256, long rows) {
    }

    /**
     * @param checked               false when the input has no status lookup
     * @param filterCodesNotLive    codes of the filters that the lookup does not list as live (or does not list): each is
     *                              also a problem
     * @param liveCodesNotInFilters live codes of the lookup that the filters do not keep: for the maintainer, not a problem
     */
    public record LookupCheck(boolean checked, List<String> filterCodesNotLive, List<String> liveCodesNotInFilters) {
        public LookupCheck {
            filterCodesNotLive = List.copyOf(filterCodesNotLive);
            liveCodesNotInFilters = List.copyOf(liveCodesNotInFilters);
        }
    }

    public HfConversionReport {
        files = List.copyOf(files);
        filtered = Collections.unmodifiableMap(new TreeMap<>(filtered));
        errors = Collections.unmodifiableMap(new TreeMap<>(errors));
        deadByStatus = Collections.unmodifiableMap(new TreeMap<>(deadByStatus));
        problems = List.copyOf(problems);
    }

    public long filteredTotal() {
        return filtered.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The report as JSON, in a fixed key order, ending with a line break. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", FORMAT);
        json.put("completeness", completeness.name());
        json.put("strict", strict);
        json.put("filtersVersion", filtersVersion);
        json.put("filtersHash", filtersHash);
        json.put("rulesetVersion", rulesetVersion);
        json.put("minYear", minYear);
        json.put("input", input);
        json.put("remote", remote);
        Map<String, Object> sourceJson = new LinkedHashMap<>();
        sourceJson.put("source_name", source.sourceName());
        sourceJson.put("url", source.url());
        sourceJson.put("license", source.license());
        sourceJson.put("source_date", source.sourceDate());
        json.put("source", sourceJson);
        if (dataset == null) {
            json.put("dataset", null);
        } else {
            Map<String, Object> lineage = new LinkedHashMap<>();
            lineage.put("buildId", dataset.buildId());
            lineage.put("builtAt", dataset.builtAt());
            lineage.put("dataCurrentThrough", dataset.dataCurrentThrough());
            lineage.put("annualCutoff", dataset.annualCutoff());
            lineage.put("caseFileRows", dataset.caseFileRows());
            lineage.put("intlClassRows", dataset.intlClassRows());
            json.put("dataset", lineage);
        }
        List<Map<String, Object>> fileJson = new ArrayList<>();
        for (FileReport file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table", file.table());
            entry.put("name", file.name());
            entry.put("bytes", file.bytes());
            entry.put("sha256", file.sha256());
            entry.put("rows", file.rows());
            fileJson.add(entry);
        }
        json.put("files", fileJson);
        json.put("caseFileRows", caseFileRows);
        json.put("intlClassRows", intlClassRows);
        json.put("duplicateCaseFileRows", duplicateCaseFileRows);
        json.put("distinctSerials", distinctSerials);
        json.put("recordsWritten", recordsWritten);
        json.put("recordErrors", recordErrors);
        json.put("deadRemoved", deadRemoved);
        json.put("filteredTotal", filteredTotal());
        json.put("ignoredClassCodes", ignoredClassCodes);
        json.put("filtered", filtered);
        json.put("errors", errors);
        json.put("deadByStatus", deadByStatus);
        Map<String, Object> lookupJson = new LinkedHashMap<>();
        lookupJson.put("checked", lookup.checked());
        lookupJson.put("filterCodesNotLive", lookup.filterCodesNotLive());
        lookupJson.put("liveCodesNotInFilters", lookup.liveCodesNotInFilters());
        json.put("lookup", lookupJson);
        json.put("problems", problems);
        json.put("duckdbVersion", duckdbVersion);
        json.put("elapsedMillis", elapsedMillis);
        json.put("outputBytes", outputBytes);
        json.put("outputSha256", outputSha256);
        try {
            return JSON.writeValueAsString(json) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
