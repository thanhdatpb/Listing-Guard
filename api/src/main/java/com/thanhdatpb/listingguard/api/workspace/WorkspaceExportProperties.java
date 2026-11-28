package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.workspace.sheets.TextEncoding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of the export (ADR-063), under {@code listing-guard.workspace.export}. Only read while the workspace is on.
 *
 * @param maxListings how many listings one export may hold; a larger selection is refused, not cut
 * @param textEncoding the encoding of a tab-delimited file when the person does not choose another: one of {@code UTF-8},
 *                     {@code UTF-8-BOM}, {@code UTF-16LE}, {@code ISO-8859-1}, {@code windows-1252}
 */
@ConfigurationProperties(prefix = "listing-guard.workspace.export")
public record WorkspaceExportProperties(
        @DefaultValue("2000") int maxListings,
        @DefaultValue("UTF-8") String textEncoding) {

    public WorkspaceExportProperties {
        if (maxListings < 1) {
            throw new IllegalArgumentException("listing-guard.workspace.export.max-listings must be at least 1.");
        }
        if (TextEncoding.parse(textEncoding).isEmpty()) {
            throw new IllegalArgumentException("listing-guard.workspace.export.text-encoding must be UTF-8, UTF-8-BOM, UTF-16LE, ISO-8859-1 or windows-1252.");
        }
    }

    public TextEncoding encoding() {
        return TextEncoding.parse(textEncoding).orElseThrow();
    }
}
