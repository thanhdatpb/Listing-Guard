package com.thanhdatpb.listingguard.api.trademark.sync;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the {@code uspto-sync} job (ADR-009, PR A2d). Nothing here changes the API or the checks. Every name of the
 * USPTO Open Data Portal below (products, paths, parameters, fields, file names) is <b>unverified</b>: no ODP page could be
 * read, so each is a setting and a wrong one is fixed in configuration.
 *
 * @param dir                 the folder of the sync, relative to {@code listing-guard.trademark.catalogs-dir}
 * @param keepVersions        the newest catalog versions the sync made that it keeps (at least 2); the served one is always kept
 * @param pruneGrace          a version that was served within this time is kept, so an API that has not refreshed yet keeps it
 * @param requireBackfile     a catalog needs a complete backfile set (a daily file holds changes only)
 * @param minLoadedRatio      a new version with fewer entries than this share of the served one's is imported, not activated
 * @param minFreeDisk         stop before a copy, a download or a conversion that would leave less than this free
 * @param verifyStoredSha256  hash every stored file again before a conversion (otherwise its size only)
 * @param dailyGapWarningDays warn when two stored daily files are more days apart than this (the release calendar is unknown)
 * @param dailyNamePattern    the whole name of a daily file; groups {@code year} or {@code yy}, {@code month}, {@code day}
 * @param backfileNamePattern the whole name of a backfile file; the date groups give the last day covered, an optional
 *                            {@code part} group the part number (parts must then run from 1 without a gap)
 * @param dailyFrom           without a backfile ({@code require-backfile=false} only): the first day to list; empty for none
 */
@ConfigurationProperties(prefix = "listing-guard.trademark.uspto.sync")
public record UsptoSyncProperties(
        @DefaultValue("raw/uspto-sync") String dir,
        @DefaultValue("3") int keepVersions,
        @DefaultValue("1d") Duration pruneGrace,
        @DefaultValue("true") boolean requireBackfile,
        @DefaultValue("0.9") double minLoadedRatio,
        @DefaultValue("5GB") DataSize minFreeDisk,
        @DefaultValue("true") boolean verifyStoredSha256,
        @DefaultValue("4") int dailyGapWarningDays,
        @DefaultValue(DEFAULT_DAILY_PATTERN) String dailyNamePattern,
        @DefaultValue(DEFAULT_BACKFILE_PATTERN) String backfileNamePattern,
        @DefaultValue("") String dailyFrom,
        @DefaultValue Archive archive,
        @DefaultValue Catalog catalog,
        @DefaultValue Download download) {

    /** {@code apcYYMMDD.zip}: the name the daily applications product is believed to use (unverified). */
    public static final String DEFAULT_DAILY_PATTERN =
            "(?i)apc(?<yy>[0-9]{2})(?<month>[0-9]{2})(?<day>[0-9]{2})\\.(?:zip|xml)";
    /** {@code apc18840407-YYYYMMDD-NN.zip}: the name the annual applications product is believed to use (unverified). */
    public static final String DEFAULT_BACKFILE_PATTERN =
            "(?i)apc[0-9]{8}-(?<year>[0-9]{4})(?<month>[0-9]{2})(?<day>[0-9]{2})-(?<part>[0-9]{1,3})\\.(?:zip|xml)";

    public UsptoSyncProperties {
        if (keepVersions < 2) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.keep-versions must be at least 2, got "
                    + keepVersions + ": the version served before the newest one is kept until every API has refreshed.");
        }
        if (minLoadedRatio < 0 || minLoadedRatio > 1) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.min-loaded-ratio must be from 0 to 1.");
        }
        if (pruneGrace == null || pruneGrace.isNegative()) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.prune-grace must not be negative.");
        }
        if (dailyGapWarningDays < 1) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.daily-gap-warning-days must be at least 1.");
        }
        requireDateGroups("daily-name-pattern", dailyNamePattern);
        requireDateGroups("backfile-name-pattern", backfileNamePattern);
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.dir must name a folder.");
        }
    }

    private static void requireDateGroups(String key, String pattern) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync." + key + " is not a regular expression: "
                    + e.getDescription());
        }
        var groups = compiled.namedGroups().keySet();
        if (!(groups.contains("year") || groups.contains("yy")) || !groups.contains("month") || !groups.contains("day")) {
            throw new IllegalArgumentException("listing-guard.trademark.uspto.sync." + key
                    + " needs the named groups year (or yy), month and day.");
        }
    }

    /** The limits of {@code UsptoArchive}: entries, bytes an entry may produce, and the compression ratio. */
    public record Archive(
            @DefaultValue("16") int maxEntries,
            @DefaultValue("64GB") DataSize maxEntrySize,
            @DefaultValue("200") long maxRatio) {
    }

    /**
     * What the job writes as the source of every catalog it makes ({@code trademark_catalog_source}). The source key is
     * always {@value UsptoSyncProperties.Catalog#SOURCE_KEY}, so the ruleset's TSDR links apply.
     *
     * @param license the terms the operator checked; required: the job refuses to run without it
     */
    public record Catalog(
            @DefaultValue("uspto") String labelPrefix,
            @DefaultValue("USPTO Trademark Full Text XML (No Images), applications; converted by uspto-sync")
            String sourceName,
            @DefaultValue("https://data.uspto.gov/bulkdata/datasets/trtdxfap") String url,
            @DefaultValue("") String license) {

        public static final String SOURCE_KEY = "uspto";

        public Catalog {
            if (!labelPrefix.matches("[a-z0-9][a-z0-9-]{0,20}")) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.catalog.label-prefix must match "
                        + "[a-z0-9][a-z0-9-]{0,20}, got '" + labelPrefix + "'.");
            }
            if (labelPrefix.startsWith("demo")) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.catalog.label-prefix must not start "
                        + "with 'demo': a catalog named demo-... is the DEMO catalog and is never activated.");
            }
        }
    }

    /** Field names of one file in the listing; {@code checksum} empty means the listing gives none. */
    public record Fields(
            @DefaultValue("fileName") String name,
            @DefaultValue("fileSize") String size,
            @DefaultValue("fileDownloadURI") String url,
            @DefaultValue("") String checksum) {
    }

    /**
     * The download from the ODP Bulk Datasets API, off by default and unverified (ADR-009, PR A2d, Decision 5).
     *
     * @param apiKey the key, only from {@code LISTING_GUARD_USPTO_ODP_API_KEY}; sent only to the host of {@code baseUrl}
     */
    public record Download(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("X-API-KEY") String apiKeyHeader,
            @DefaultValue("https://api.uspto.gov") String baseUrl,
            @DefaultValue("/api/v1/datasets/products/{product}") String productPath,
            @DefaultValue("/api/v1/datasets/products/files/{product}/{file}") String filePath,
            @DefaultValue("TRTDXFAP") String dailyProduct,
            @DefaultValue("TRTYRAP") String backfileProduct,
            @DefaultValue("fileDataFromDate={from}&fileDataToDate={to}&includeFiles=true") String dailyQuery,
            @DefaultValue("includeFiles=true") String backfileQuery,
            @DefaultValue("31") int listWindowDays,
            @DefaultValue("/bulkDataProductBag/0/productFileBag/fileDataBag") String filesPointer,
            @DefaultValue Fields fields,
            @DefaultValue("SHA-256") String checksumAlgorithm,
            @DefaultValue("api.uspto.gov") List<String> allowedHosts,
            @DefaultValue("5") int maxRedirects,
            @DefaultValue("2s") Duration minInterval,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("5s") Duration initialBackoff,
            @DefaultValue("5m") Duration maxBackoff,
            @DefaultValue("30s") Duration connectTimeout,
            @DefaultValue("120s") Duration readTimeout,
            @DefaultValue("60") int maxFilesPerRun,
            @DefaultValue("20GB") DataSize maxFileSize,
            @DefaultValue("32MB") DataSize maxListingSize,
            @DefaultValue("listing-guard-uspto-sync") String userAgent) {

        public Download {
            apiKey = apiKey == null ? "" : apiKey.trim();
            allowedHosts = allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                    .toList();
            if (maxAttempts < 1 || maxRedirects < 0 || listWindowDays < 1 || maxFilesPerRun < 1) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.download: max-attempts, "
                        + "list-window-days and max-files-per-run must be at least 1, max-redirects at least 0.");
            }
            if (!checksumAlgorithm.equals("SHA-256") && !checksumAlgorithm.equals("MD5")) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.download.checksum-algorithm must be "
                        + "SHA-256 or MD5.");
            }
            if (!productPath.contains("{product}") || !filePath.contains("{product}") || !filePath.contains("{file}")) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.download: product-path needs "
                        + "{product}, file-path needs {product} and {file}.");
            }
            URI base = URI.create(baseUrl);
            String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
            boolean https = "https".equalsIgnoreCase(base.getScheme());
            boolean loopbackHttp = "http".equalsIgnoreCase(base.getScheme()) && UsptoBulkClient.isLoopback(host);
            if (!(https || loopbackHttp) || base.getRawUserInfo() != null) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.download.base-url must be https "
                        + "(plain http only to this machine), without a user name.");
            }
            if (!allowedHosts.contains(host)) {
                throw new IllegalArgumentException("listing-guard.trademark.uspto.sync.download.allowed-hosts must "
                        + "contain the host of base-url (" + host + ").");
            }
        }

        public boolean hasKey() {
            return !apiKey.isEmpty();
        }

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Download[enabled=" + enabled + ", apiKey=" + (hasKey() ? "(set)" : "(empty)") + ", apiKeyHeader="
                    + apiKeyHeader + ", baseUrl=" + baseUrl + ", productPath=" + productPath + ", filePath=" + filePath
                    + ", dailyProduct=" + dailyProduct + ", backfileProduct=" + backfileProduct + ", allowedHosts="
                    + allowedHosts + ", maxAttempts=" + maxAttempts + ", minInterval=" + minInterval + "]";
        }
    }
}
