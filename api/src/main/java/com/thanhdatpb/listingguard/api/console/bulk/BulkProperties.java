package com.thanhdatpb.listingguard.api.console.bulk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits of the console bulk check (ADR-029).
 *
 * @param maxRows  most data rows of one paste or file (a header row is not counted, a blank row is); a larger input is refused
 *                 as a whole, never cut, because a cut table would shift the rows against the Sheet it came from
 * @param maxBytes most bytes of one pasted text or one file
 */
@ConfigurationProperties(prefix = "listing-guard.console.bulk")
public record BulkProperties(@DefaultValue("200") int maxRows, @DefaultValue("1MB") DataSize maxBytes) {

    private static final long FORM_OVERHEAD_BYTES = 64 * 1024;

    public BulkProperties {
        if (maxRows < 1) {
            throw new IllegalArgumentException("listing-guard.console.bulk.max-rows must be at least 1.");
        }
        if (maxBytes.toBytes() < 1024) {
            throw new IllegalArgumentException("listing-guard.console.bulk.max-bytes must be at least 1KB.");
        }
    }

    /**
     * The most a request to {@code /console/bulk/prepare} or {@code /console/bulk/run} may declare: both pages post
     * {@code multipart/form-data} (a text in a URL-encoded field can grow several-fold, and Tomcat stops reading a URL-encoded body at
     * {@code server.tomcat.max-http-form-post-size}, 2 MB), so the text and a file travel as they are. The request holds the text or
     * one file, and the {@code run} request the text again; the limits above are the real ones and are checked by the controller.
     * {@code max-bytes} must also stay below {@code spring.servlet.multipart.max-file-size} (10 MB by default), which the container
     * applies to every part.
     */
    public long requestLimitBytes() {
        return maxBytes.toBytes() * 2 + FORM_OVERHEAD_BYTES;
    }
}
