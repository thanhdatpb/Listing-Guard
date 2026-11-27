package com.thanhdatpb.listingguard.workspace.sheets;

/**
 * A file the workspace refuses to read (ADR-059). The message never holds a cell value or a file name: it is safe to log and, through
 * {@link #reason()}, to turn into a sentence of the Console.
 */
public class SheetRejected extends RuntimeException {

    public enum Reason {
        TOO_LARGE, TOO_MANY_ROWS, TOO_MANY_COLUMNS, ENCRYPTED, CORRUPT, ZIP_BOMB, UNSUPPORTED, EMPTY, SHEET_NOT_FOUND,
        /** writing: a cell is longer than Excel's 32,767 characters */
        CELL_TOO_LONG,
        /** writing: a cell holds a character the chosen text encoding cannot write */
        UNENCODABLE
    }

    private final Reason reason;

    public SheetRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SheetRejected(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
