package com.thanhdatpb.listingguard.trademark.catalog.uspto;

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
 * What one conversion did (ADR-009, PR A2c; format {@code listing-guard/uspto-convert-report/v2}): the files it read,
 * how many records they held and what became of each one.
 *
 * <p>Every record of a file that was read is counted exactly once, so
 * {@code recordsRead == recordsWritten + recordErrors + superseded + deadRemoved + filteredTotal()}. A record is counted
 * under the first check it fails, in the order of {@link UsptoCatalogConverter}: a record error, no information about the
 * status, replaced by a later record ({@code superseded}), not live in its latest record ({@code deadRemoved}), then the
 * other reasons ({@code filtered}). A reason is one of a fixed set of words, or {@code status_dropped:<code>} or
 * {@code status_unmapped:<code>} for a status code (a code that is not three digits is reported as {@code invalid} or
 * {@code missing}, so the number of reasons stays small). A report holds no mark and no serial number.
 *
 * @param completeness      {@code INCOMPLETE} when a file was left out or a record had an error, so the output cannot be
 *                          trusted for a deployment
 * @param strict            whether the run stopped at the first problem
 * @param otherVersionAllowed true when a file declared another {@code version-no} and the configuration allowed it
 * @param files             every file given, in the order they were read, with what was found
 * @param recordsRead       the {@code case-file} records of the files that were read
 * @param recordsWritten    the records written to the output
 * @param recordErrors      records that could not be placed (see {@code errors} for the codes)
 * @param superseded        records replaced by a record with a later transaction date for the same serial number
 * @param deadRemoved       records that were the latest of their serial number and whose status code is not live
 * @param distinctSerials   the serial numbers that took part in the ordering
 * @param ignoredClassCodes international codes that are not a Nice class from 1 to 45 (for example {@code A},
 *                          {@code B} or {@code 200}), left out of the records that were written
 * @param filtered          every other reason and its count, in alphabetical order
 * @param errors            the record errors by code
 * @param deadByStatus      {@code deadRemoved} by status code
 * @param errorDetails      the first record errors with the file and the place (at most {@link #MAX_ERROR_DETAILS})
 * @param outputSha256      SHA-256 of the output: the {@code sha256} to put in {@code catalogs/manifest.yaml}
 */
public record ConversionReport(
        Completeness completeness,
        boolean strict,
        String statusMapVersion,
        String statusMapHash,
        String filtersVersion,
        String filtersHash,
        boolean otherVersionAllowed,
        List<FileReport> files,
        long recordsRead,
        long recordsWritten,
        long recordErrors,
        long superseded,
        long deadRemoved,
        long distinctSerials,
        long ignoredClassCodes,
        Map<String, Long> filtered,
        Map<String, Long> errors,
        Map<String, Long> deadByStatus,
        List<RecordError> errorDetails,
        long outputBytes,
        String outputSha256) {

    public static final String FORMAT = "listing-guard/uspto-convert-report/v2";
    /** The most record errors listed one by one; the counts are complete. */
    public static final int MAX_ERROR_DETAILS = 100;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The status of a file: read, or left out because it could not be read. */
    public enum FileStatus {
        OK, FAILED
    }

    /**
     * One input file.
     *
     * @param name       the name the converter was given (a path inside {@code catalogs/raw})
     * @param bytes      the size read; 0 for a file that failed
     * @param sha256     SHA-256 of the whole file; null for a file that failed
     * @param versionNo  {@code version/version-no}, null when the file did not get that far
     * @param versionDate {@code version/version-date}
     * @param dataAvailable false when the file says it holds no data ({@code data-available-code} {@code N})
     * @param records    the records of the file; 0 for a file that failed
     * @param status     {@code OK} or {@code FAILED}
     * @param error      why a file failed (never a mark); null for {@code OK}
     */
    public record FileReport(String name, long bytes, String sha256, String versionNo, String versionDate,
                             boolean dataAvailable, long records, FileStatus status, String error) {
    }

    /**
     * A record that could not be placed in the order.
     *
     * @param file   the file name
     * @param record the 1-based place of the record among the case files of the file
     * @param line   the line of its start tag, 0 when unknown
     * @param column the column of its start tag, 0 when unknown
     * @param code   {@code missing_serial}, {@code invalid_serial}, {@code missing_transaction_date} or
     *               {@code invalid_transaction_date}
     */
    public record RecordError(String file, long record, long line, long column, String code) {
    }

    public ConversionReport {
        files = List.copyOf(files);
        filtered = Collections.unmodifiableMap(new TreeMap<>(filtered));
        errors = Collections.unmodifiableMap(new TreeMap<>(errors));
        deadByStatus = Collections.unmodifiableMap(new TreeMap<>(deadByStatus));
        errorDetails = List.copyOf(errorDetails);
    }

    /** Records dropped by a filter, whatever the reason. */
    public long filteredTotal() {
        return filtered.values().stream().mapToLong(Long::longValue).sum();
    }

    public long filesFailed() {
        return files.stream().filter(f -> f.status() == FileStatus.FAILED).count();
    }

    /** The report as JSON, in a fixed key order, ending with a line break. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", FORMAT);
        json.put("completeness", completeness.name());
        json.put("strict", strict);
        json.put("statusMapVersion", statusMapVersion);
        json.put("statusMapHash", statusMapHash);
        json.put("filtersVersion", filtersVersion);
        json.put("filtersHash", filtersHash);
        json.put("otherVersionAllowed", otherVersionAllowed);
        List<Map<String, Object>> fileJson = new ArrayList<>();
        for (FileReport file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", file.name());
            entry.put("status", file.status().name());
            entry.put("bytes", file.bytes());
            entry.put("sha256", file.sha256());
            entry.put("versionNo", file.versionNo());
            entry.put("versionDate", file.versionDate());
            entry.put("dataAvailable", file.dataAvailable());
            entry.put("records", file.records());
            entry.put("error", file.error());
            fileJson.add(entry);
        }
        json.put("filesRead", files.size() - filesFailed());
        json.put("filesFailed", filesFailed());
        json.put("files", fileJson);
        json.put("recordsRead", recordsRead);
        json.put("recordsWritten", recordsWritten);
        json.put("recordErrors", recordErrors);
        json.put("superseded", superseded);
        json.put("deadRemoved", deadRemoved);
        json.put("filteredTotal", filteredTotal());
        json.put("distinctSerials", distinctSerials);
        json.put("ignoredClassCodes", ignoredClassCodes);
        json.put("filtered", filtered);
        json.put("errors", errors);
        json.put("deadByStatus", deadByStatus);
        List<Map<String, Object>> detailJson = new ArrayList<>();
        for (RecordError error : errorDetails) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", error.file());
            entry.put("record", error.record());
            entry.put("line", error.line());
            entry.put("column", error.column());
            entry.put("code", error.code());
            detailJson.add(entry);
        }
        json.put("errorDetails", detailJson);
        json.put("outputBytes", outputBytes);
        json.put("outputSha256", outputSha256);
        try {
            return JSON.writeValueAsString(json) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
