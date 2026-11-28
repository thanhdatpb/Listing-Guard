package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.workspace.sheets.SheetLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the listing workspace (ADR-059). Everything is off by default: while {@code enabled} is false no bean of the
 * workspace exists, no menu entry shows and every {@code /console/workspace} path is a 404.
 *
 * @param memberEditScope who of the MEMBERs may change a listing: {@code OWN} the listings they are responsible for (default), {@code ALL}
 *                        every listing of the client. Managing accounts and templates, changing the responsible person and deleting stay ADMIN's
 * @param maxTemplateBytes the largest template file an ADMIN may upload
 * @param importMaxBytes   the largest file or paste of one import; must stay below {@code spring.servlet.multipart.max-file-size}
 * @param importMaxRows    the most data rows of one import; a larger file is refused whole
 * @param historyKeep      how many versions of a listing are kept
 * @param stageTtl         how long an uploaded file waits between the steps of an import
 */
@ConfigurationProperties(prefix = "listing-guard.workspace")
public record WorkspaceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("OWN") EditScope memberEditScope,
        @DefaultValue("10MB") DataSize maxTemplateBytes,
        @DefaultValue("20MB") DataSize importMaxBytes,
        @DefaultValue("10000") int importMaxRows,
        @DefaultValue("1500") int maxColumns,
        @DefaultValue("20") int historyKeep,
        @DefaultValue("1h") Duration stageTtl,
        @DefaultValue("0.01") double minInflateRatio,
        @DefaultValue("256MB") DataSize maxEntrySize,
        @DefaultValue("64MB") DataSize maxTextSize) {

    /** The rows above the data (settings, labels, field names) that come on top of {@code importMaxRows}. */
    public static final int HEADER_ALLOWANCE = 200;
    /** What the multipart envelope adds to a file. */
    private static final long FORM_OVERHEAD_BYTES = 64 * 1024;

    public enum EditScope {
        OWN, ALL
    }

    public WorkspaceProperties {
        if (importMaxRows < 1 || maxColumns < 1 || historyKeep < 1) {
            throw new IllegalArgumentException("listing-guard.workspace: import-max-rows, max-columns and history-keep must be at least 1.");
        }
        if (importMaxBytes.toBytes() < 1024 || maxTemplateBytes.toBytes() < 1024) {
            throw new IllegalArgumentException("listing-guard.workspace: the byte limits must be at least 1KB.");
        }
        if (stageTtl.isNegative() || stageTtl.isZero()) {
            throw new IllegalArgumentException("listing-guard.workspace.stage-ttl must be positive.");
        }
    }

    /** The reader's limits for a template upload or for an import. */
    public SheetLimits limits(boolean template) {
        return new SheetLimits(template ? maxTemplateBytes.toBytes() : importMaxBytes.toBytes(), importMaxRows + HEADER_ALLOWANCE, maxColumns,
                minInflateRatio, maxEntrySize.toBytes(), maxTextSize.toBytes());
    }

    /**
     * The limits of a written file (ADR-063): the rows and columns of an import, and the larger of the two file limits, because the template that
     * is copied was accepted under {@code maxTemplateBytes} and the file that comes out may hold up to {@code importMaxRows} rows.
     */
    public SheetLimits exportLimits() {
        return new SheetLimits(Math.max(importMaxBytes.toBytes(), maxTemplateBytes.toBytes()), importMaxRows + HEADER_ALLOWANCE, maxColumns,
                minInflateRatio, maxEntrySize.toBytes(), maxTextSize.toBytes());
    }

    /** The largest request of the upload paths: the bigger file limit plus the multipart envelope. */
    public long requestLimitBytes() {
        return Math.max(importMaxBytes.toBytes(), maxTemplateBytes.toBytes()) + FORM_OVERHEAD_BYTES;
    }
}
