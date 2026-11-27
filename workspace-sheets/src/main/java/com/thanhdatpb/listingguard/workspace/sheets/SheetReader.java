package com.thanhdatpb.listingguard.workspace.sheets;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.util.List;

/**
 * The entry point of the workspace's file reading (ADR-059). Every call checks the size first, decides the format from the bytes, and
 * refuses what it cannot read safely: encrypted or password-protected files, binary Office files other than .xlsx/.xlsm, zip bombs,
 * damaged packages and files over the limits. A macro is never run and a formula is never recalculated. Nothing is written to disk
 * except a private temporary copy of a large workbook, deleted before the call returns.
 */
public final class SheetReader {

    /** The rows of each sheet that {@link #inspect} returns. */
    public static final int PREVIEW_ROWS = 40;

    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private SheetReader() {
    }

    /** The format of the bytes, or a {@link SheetRejected}. */
    public static SheetFormat detect(byte[] bytes, SheetLimits limits) {
        if (bytes == null || bytes.length == 0) {
            throw new SheetRejected(Reason.EMPTY, "The file is empty.");
        }
        if (bytes.length > limits.maxBytes()) {
            throw new SheetRejected(Reason.TOO_LARGE, "The file is larger than " + limits.maxBytes() + " bytes.");
        }
        if (startsWith(bytes, ZIP)) {
            return SheetFormat.XLSX;
        }
        if (startsWith(bytes, OLE)) {
            throw new SheetRejected(encrypted(bytes) ? Reason.ENCRYPTED : Reason.UNSUPPORTED,
                    "The file is a binary Office container: an encrypted workbook or an old .xls. Neither is read.");
        }
        if (!DelimitedReader.looksLikeText(bytes)) {
            throw new SheetRejected(Reason.UNSUPPORTED, "The file is not a workbook or a text table.");
        }
        return DelimitedReader.format(DelimitedReader.delimiter(DelimitedReader.decode(bytes)));
    }

    /** The sheets of a file with their first rows. A text table has one sheet, named {@code data}. */
    public static WorkbookInfo inspect(byte[] bytes, SheetLimits limits) {
        SheetFormat format = detect(bytes, limits);
        if (format.isExcel()) {
            return XlsxReader.inspect(bytes, limits);
        }
        SheetGrid preview = DelimitedReader.read(bytes, limits, PREVIEW_ROWS);
        return new WorkbookInfo(format, false, List.of(new WorkbookInfo.SheetInfo(DelimitedReader.SHEET_NAME, preview)));
    }

    /** All rows of one sheet. For a text table the sheet name is ignored. */
    public static SheetGrid read(byte[] bytes, String sheetName, SheetLimits limits) {
        SheetFormat format = detect(bytes, limits);
        if (format.isExcel()) {
            return XlsxReader.read(bytes, sheetName, limits);
        }
        return DelimitedReader.read(bytes, limits, 0);
    }

    /** An OLE container with an {@code EncryptedPackage} stream is an .xlsx that a password protects. */
    private static boolean encrypted(byte[] bytes) {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(new java.io.ByteArrayInputStream(bytes))) {
            return fs.getRoot().hasEntry("EncryptedPackage") || fs.getRoot().hasEntry("EncryptionInfo");
        } catch (java.io.IOException | RuntimeException e) {
            // an unreadable container is not shown as "encrypted": it is simply not a file this reads
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
