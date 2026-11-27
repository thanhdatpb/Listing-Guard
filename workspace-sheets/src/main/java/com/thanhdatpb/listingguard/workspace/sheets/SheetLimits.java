package com.thanhdatpb.listingguard.workspace.sheets;

/**
 * What a file may cost before it is refused as a whole (ADR-059). A limit is never a cut: a file over one is rejected with a
 * {@link SheetRejected}, so a listing is never silently half imported.
 *
 * @param maxBytes       the size of the file as uploaded
 * @param maxRows        the highest row number that may hold content (rows beyond it that are empty are ignored)
 * @param maxColumns     the widest row that may hold content
 * @param minInflateRatio the smallest compressed/uncompressed ratio of a zip entry; a smaller one is a zip bomb (POI's default is 0.01)
 * @param maxEntrySize   the most bytes one zip entry may expand to
 * @param maxTextSize    the most characters of text one XML part may hold (POI's guard against huge shared string tables)
 */
public record SheetLimits(long maxBytes, int maxRows, int maxColumns, double minInflateRatio, long maxEntrySize,
                          long maxTextSize) {

    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    public static final int DEFAULT_MAX_ROWS = 10_000;
    public static final int DEFAULT_MAX_COLUMNS = 1_500;
    public static final double DEFAULT_MIN_INFLATE_RATIO = 0.01;
    public static final long DEFAULT_MAX_ENTRY_SIZE = 256L * 1024 * 1024;
    public static final long DEFAULT_MAX_TEXT_SIZE = 64L * 1024 * 1024;

    public SheetLimits {
        if (maxBytes < 1 || maxRows < 1 || maxColumns < 1 || maxEntrySize < 1 || maxTextSize < 1) {
            throw new IllegalArgumentException("Every limit must be positive.");
        }
        if (!(minInflateRatio > 0 && minInflateRatio <= 1)) {
            throw new IllegalArgumentException("The minimum inflate ratio must be in (0, 1].");
        }
    }

    public static SheetLimits defaults() {
        return new SheetLimits(DEFAULT_MAX_BYTES, DEFAULT_MAX_ROWS, DEFAULT_MAX_COLUMNS, DEFAULT_MIN_INFLATE_RATIO,
                DEFAULT_MAX_ENTRY_SIZE, DEFAULT_MAX_TEXT_SIZE);
    }

    public SheetLimits withMaxBytes(long bytes) {
        return new SheetLimits(bytes, maxRows, maxColumns, minInflateRatio, maxEntrySize, maxTextSize);
    }

    public SheetLimits withMaxRows(int rows) {
        return new SheetLimits(maxBytes, rows, maxColumns, minInflateRatio, maxEntrySize, maxTextSize);
    }

    public SheetLimits withMaxEntrySize(long bytes) {
        return new SheetLimits(maxBytes, maxRows, maxColumns, minInflateRatio, bytes, maxTextSize);
    }
}
