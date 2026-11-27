package com.thanhdatpb.listingguard.workspace.sheets;

import java.util.List;

/**
 * What a first look at a file shows: its format, whether it carries a macro project (informational: a macro is never run) and each
 * sheet with its first rows, so a person can pick the sheet and the row of field names.
 */
public record WorkbookInfo(SheetFormat format, boolean macroProject, List<SheetInfo> sheets) {

    public WorkbookInfo {
        sheets = List.copyOf(sheets);
    }

    /** A sheet and its first rows (a {@link SheetGrid} of at most {@link SheetReader#PREVIEW_ROWS} rows). */
    public record SheetInfo(String name, SheetGrid preview) {
    }
}
