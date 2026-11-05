package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one conversion did (ADR-009, PR A2b): the version the file declared, how many records it held, how many were
 * written, and how many were dropped for each reason. {@code recordsRead == recordsWritten + droppedTotal()}.
 *
 * <p>A reason is one of a fixed set of words, or {@code status_dropped:<code>} or {@code status_unmapped:<code>} for a
 * status code (a code that is not three digits is reported as {@code invalid} or {@code missing}, so the number of
 * reasons stays small). A record is counted under the first check it fails, in the order of
 * {@link UsptoDailyConverter}. A report holds no mark and no serial number.
 *
 * @param statusMapVersion    the version of the status map used
 * @param statusMapHash       the content hash of that map
 * @param versionNo           {@code version/version-no} of the file, trimmed
 * @param versionDate         {@code version/version-date} of the file, trimmed
 * @param otherVersionAllowed true when the file declared another version and the configuration allowed it
 * @param dataAvailable       false when the file says it holds no data ({@code data-available-code} {@code N})
 * @param ignoredClassCodes   international codes that are not a Nice class from 1 to 45 (for example {@code A},
 *                            {@code B} or {@code 200}), left out of the records that were written
 * @param dropped             the reasons and their counts, in alphabetical order
 * @param inputSha256         SHA-256 of the whole input
 * @param outputSha256        SHA-256 of the output: the {@code sha256} to put in {@code catalogs/manifest.yaml}
 */
public record ConversionReport(
        String statusMapVersion,
        String statusMapHash,
        String versionNo,
        String versionDate,
        boolean otherVersionAllowed,
        boolean dataAvailable,
        long recordsRead,
        long recordsWritten,
        long ignoredClassCodes,
        Map<String, Long> dropped,
        long inputBytes,
        String inputSha256,
        long outputBytes,
        String outputSha256) {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ConversionReport {
        dropped = Collections.unmodifiableMap(new TreeMap<>(dropped));
    }

    public long droppedTotal() {
        return dropped.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The report as JSON, in a fixed key order, ending with a line break. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", "listing-guard/uspto-convert-report/v1");
        json.put("statusMapVersion", statusMapVersion);
        json.put("statusMapHash", statusMapHash);
        json.put("versionNo", versionNo);
        json.put("versionDate", versionDate);
        json.put("otherVersionAllowed", otherVersionAllowed);
        json.put("dataAvailable", dataAvailable);
        json.put("recordsRead", recordsRead);
        json.put("recordsWritten", recordsWritten);
        json.put("recordsDropped", droppedTotal());
        json.put("ignoredClassCodes", ignoredClassCodes);
        json.put("dropped", dropped);
        json.put("inputBytes", inputBytes);
        json.put("inputSha256", inputSha256);
        json.put("outputBytes", outputBytes);
        json.put("outputSha256", outputSha256);
        try {
            return JSON.writeValueAsString(json) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
