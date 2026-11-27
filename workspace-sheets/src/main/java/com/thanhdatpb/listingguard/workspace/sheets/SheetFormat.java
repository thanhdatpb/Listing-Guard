package com.thanhdatpb.listingguard.workspace.sheets;

/** The formats the workspace reads. The format is decided from the bytes, never from the file name. */
public enum SheetFormat {
    XLSX, XLSM, TSV, CSV;

    public boolean isExcel() {
        return this == XLSX || this == XLSM;
    }
}
