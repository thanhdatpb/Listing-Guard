package com.thanhdatpb.listingguard.api.trademark.tsdr;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the TSDR status check (ADR-009, "TSDR status check"): the one-shot job {@code tsdr-status} and the optional
 * display of the last status next to a trademark finding. Nothing here changes the ruleset, the matcher, the canonical input
 * hash or the analysis fingerprint.
 *
 * <p><b>What is verified.</b> The base URL, the path of the XML status document and the rate limit (60 requests per key per
 * minute) are read from USPTO's own page https://tsdr.uspto.gov/faqview. The name of the key header
 * ({@code USPTO-API-KEY}) comes from a third-party README and a search snippet quoting the key-manager guide, which could not
 * be read; the names of the elements that hold the status, and the claim that an unknown serial number answers {@code 404},
 * are from memory of the document format. All of those are settings so a correction needs no code, and none is marked
 * verified in the ADR beyond what the page says.
 *
 * @param dir                    folder of the job's reports, relative to {@code listing-guard.trademark.catalogs-dir}
 * @param maxMarksPerRun         the most marks one live run asks the TSDR about
 * @param unmatchedPerRun        of those, how many may be marks no recent finding named (matched marks come first)
 * @param matchWindow            a finding counts as "recent" for this long (a result is kept 30 days, so more changes nothing)
 * @param recheckInterval        a mark with an {@code OK} or {@code NOT_FOUND} answer is not asked again within this time
 * @param errorRetryInterval     a mark whose last try failed is not tried again within this time
 * @param maxConsecutiveFailures the run stops after this many marks in a row that failed
 * @param keepReports            how many report folders are kept
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.tsdr")
public record TsdrProperties(
        @DefaultValue("raw/tsdr") String dir,
        @DefaultValue("100") int maxMarksPerRun,
        @DefaultValue("0") int unmatchedPerRun,
        @DefaultValue("30d") Duration matchWindow,
        @DefaultValue("30d") Duration recheckInterval,
        @DefaultValue("1d") Duration errorRetryInterval,
        @DefaultValue("5") int maxConsecutiveFailures,
        @DefaultValue("12") int keepReports,
        @DefaultValue Fields fields,
        @DefaultValue Download download,
        @DefaultValue FindingStatus findingStatus) {

    public TsdrProperties {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("listing-guard.trademark.tsdr.dir must name a folder.");
        }
        if (maxMarksPerRun < 1 || maxConsecutiveFailures < 1 || keepReports < 1) {
            throw new IllegalArgumentException("listing-guard.trademark.tsdr: max-marks-per-run, max-consecutive-failures and "
                    + "keep-reports must be at least 1.");
        }
        if (unmatchedPerRun < 0 || unmatchedPerRun > maxMarksPerRun) {
            throw new IllegalArgumentException("listing-guard.trademark.tsdr.unmatched-per-run must be from 0 to "
                    + "max-marks-per-run (" + maxMarksPerRun + "), got " + unmatchedPerRun + ".");
        }
        for (Map.Entry<String, Duration> entry : Map.of("match-window", matchWindow, "recheck-interval", recheckInterval,
                "error-retry-interval", errorRetryInterval).entrySet()) {
            if (entry.getValue().isNegative() || entry.getValue().isZero()) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr." + entry.getKey() + " must be positive.");
            }
        }
    }

    /**
     * Names that hold the status in an answer, matched without regard to case on an XML element's local name or a JSON field.
     * The first name (in list order) that has a plain value anywhere in the document wins. Unverified.
     */
    public record Fields(
            @DefaultValue({"MarkCurrentStatusCode", "status"}) List<String> statusCodeNames,
            @DefaultValue({"MarkCurrentStatusExternalDescriptionText", "extStatusDesc"}) List<String> statusDescriptionNames,
            @DefaultValue({"MarkCurrentStatusDate", "statusDate"}) List<String> statusDateNames) {

        public Fields {
            statusCodeNames = clean("status-code-names", statusCodeNames);
            statusDescriptionNames = clean("status-description-names", statusDescriptionNames);
            statusDateNames = clean("status-date-names", statusDateNames);
        }

        private static List<String> clean(String key, List<String> names) {
            List<String> cleaned = names.stream().map(n -> n.trim().toLowerCase(Locale.ROOT)).filter(n -> !n.isEmpty()).toList();
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.fields." + key + " needs at least one name.");
            }
            return cleaned;
        }
    }

    /**
     * The calls to the TSDR. Off by default: the job refuses a live run unless {@code enabled} and a key are set.
     *
     * @param apiKey the key, only from {@code LISTING_GUARD_TSDR_API_KEY}; sent only to the host of {@code baseUrl}
     */
    public record Download(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("USPTO-API-KEY") String apiKeyHeader,
            @DefaultValue("https://tsdrapi.uspto.gov") String baseUrl,
            @DefaultValue("/ts/cd/casestatus/sn{serial}/info.xml") String statusPath,
            @DefaultValue("tsdrapi.uspto.gov") List<String> allowedHosts,
            @DefaultValue("2s") Duration minInterval,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("5s") Duration initialBackoff,
            @DefaultValue("2m") Duration maxBackoff,
            @DefaultValue("15s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("4MB") DataSize maxResponseSize,
            @DefaultValue("listing-guard-tsdr-status") String userAgent) {

        public Download {
            apiKey = apiKey == null ? "" : apiKey.trim();
            allowedHosts = allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.download.max-attempts must be at least 1.");
            }
            if (minInterval.isNegative() || initialBackoff.isNegative() || maxBackoff.isNegative()) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.download: intervals must not be negative.");
            }
            if (statusPath.indexOf("{serial}") < 0 || statusPath.indexOf("{serial}") != statusPath.lastIndexOf("{serial}")
                    || !statusPath.startsWith("/") || statusPath.contains("?") || statusPath.contains("#")
                    || statusPath.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.download.status-path must start with / and "
                        + "contain {serial} once, with no query, fragment or space.");
            }
            URI base = URI.create(baseUrl);
            String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
            boolean https = "https".equalsIgnoreCase(base.getScheme());
            boolean loopbackHttp = "http".equalsIgnoreCase(base.getScheme()) && TsdrClient.isLoopback(host);
            if (!(https || loopbackHttp) || base.getRawUserInfo() != null || base.getRawQuery() != null) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.download.base-url must be https (plain http "
                        + "only to this machine), without a user name or a query.");
            }
            if (!allowedHosts.contains(host)) {
                throw new IllegalArgumentException("listing-guard.trademark.tsdr.download.allowed-hosts must contain the host "
                        + "of base-url (" + host + ").");
            }
        }

        public boolean hasKey() {
            return !apiKey.isEmpty();
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Download[enabled=" + enabled + ", apiKey=" + (hasKey() ? "(set)" : "(empty)") + ", apiKeyHeader="
                    + apiKeyHeader + ", baseUrl=" + baseUrl + ", statusPath=" + statusPath + ", minInterval=" + minInterval
                    + ", maxAttempts=" + maxAttempts + "]";
        }
    }

    /**
     * Whether a trademark finding shows the last TSDR status of its source records. Off by default; off means the answer is
     * what it was, byte for byte. On, it is added when the answer is given (never stored with the result), so it changes no
     * level, no cache identity and no analysis version.
     */
    public record FindingStatus(@DefaultValue("false") boolean enabled) {
    }
}
