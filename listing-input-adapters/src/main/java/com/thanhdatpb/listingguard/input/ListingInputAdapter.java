package com.thanhdatpb.listingguard.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A configured, read-only source format adapter. No adapter writes to a marketplace. */
public interface ListingInputAdapter {
    String id();

    String marketplace();

    Set<String> formats();

    /** Detects the format from a parsed header; a score of zero means unsupported. */
    Detection detect(List<String> header);

    /** Opens a bounded, streaming source and returns one normalized listing at a time. */
    ListingReader open(InputStream input, Limits limits) throws IOException;

    record Detection(boolean supported, int matchedColumns, List<String> missingRequiredColumns, List<String> ambiguousColumns) {
        public Detection(boolean supported, int matchedColumns, List<String> missingRequiredColumns) {
            this(supported, matchedColumns, missingRequiredColumns, List.of());
        }
        public Detection {
            missingRequiredColumns = List.copyOf(missingRequiredColumns);
            ambiguousColumns = List.copyOf(ambiguousColumns);
        }
    }

    record Limits(long maxBytes, long maxRows) {
        public Limits {
            if (maxBytes < 1 || maxRows < 1) throw new IllegalArgumentException("limits must be positive");
        }
    }

    record FieldError(String field, String code, String message) { }

    /**
     * One source record. {@code id} is the value of the template's {@code id_column} (a SKU or handle), null when the template
     * names none or the cell is blank; it names the record in a report and is not a listing field.
     */
    record ListingRecord(long line, Map<String, Object> listing, List<FieldError> errors,
                         String familyId, String familyRole, String id) {
        public ListingRecord(long line, Map<String, Object> listing, List<FieldError> errors) {
            this(line, listing, errors, null, null, null);
        }
        public ListingRecord(long line, Map<String, Object> listing, List<FieldError> errors,
                             String familyId, String familyRole) {
            this(line, listing, errors, familyId, familyRole, null);
        }
        public ListingRecord {
            listing = Collections.unmodifiableMap(new LinkedHashMap<>(listing));
            errors = List.copyOf(errors);
        }
    }

    interface ListingReader extends AutoCloseable {
        ListingRecord next() throws IOException;
        @Override void close() throws IOException;
    }
}
