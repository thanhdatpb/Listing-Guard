package com.thanhdatpb.listingguard.api.web;

import java.util.regex.Pattern;

/**
 * The release version of the running server, sent as {@code X-Listing-Guard-Version} on every {@code /api/v1} response
 * (ADR-022, section 7): for example {@code 0.8.0}, or {@code dev} for a build without a release version. It comes from the
 * Maven property {@code listing-guard.release}, written into {@code META-INF/build-info.properties} at build time.
 *
 * @param value the header value; {@value #DEV} or a release version without the {@code v} of the tag
 */
public record ServerVersion(String value) {

    public static final String DEV = "dev";

    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

    public ServerVersion {
        if (value == null || !(DEV.equals(value) || RELEASE.matcher(value).matches())) {
            throw new IllegalStateException("The release version of this build is not usable as X-Listing-Guard-Version: \"" + value
                    + "\". Build with -Dlisting-guard.release=<major.minor.patch> (ADR-022), or leave it out for \"dev\".");
        }
    }

    /** The version from the build information: blank or absent is {@value #DEV}; the {@code v} of a tag is dropped. */
    public static ServerVersion fromBuild(String release) {
        if (release == null || release.isBlank()) {
            return new ServerVersion(DEV);
        }
        String trimmed = release.trim();
        return new ServerVersion(trimmed.matches("v\\d.*") ? trimmed.substring(1) : trimmed);
    }
}
