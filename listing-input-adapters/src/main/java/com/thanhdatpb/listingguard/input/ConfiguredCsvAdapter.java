package com.thanhdatpb.listingguard.input;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A CSV adapter whose columns are mapped to the input schema by YAML configuration. */
public final class ConfiguredCsvAdapter implements ListingInputAdapter {
    private static final CsvMapper CSV = new CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY)
            .enable(CsvParser.Feature.SKIP_EMPTY_LINES);
    private final String id;
    private final String marketplace;
    private final Set<String> formats;
    private final char delimiter;
    private final Charset encoding;
    private final Map<String, String> fields;
    private final Map<String, List<String>> multiFields;
    private final Map<String, String> separators;
    private final List<String> required;
    private final FamilyColumns family;
    private final String idColumn;

    public record FamilyColumns(String roleColumn, String parentValue, String childValue,
                                String skuColumn, String parentSkuColumn) { }

    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter,
                                Map<String, String> fields, List<String> required) {
        this(id, marketplace, formats, delimiter, fields, required, null);
    }

    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter,
                                Map<String, String> fields, List<String> required, FamilyColumns family) {
        this(id, marketplace, formats, delimiter, fields, Map.of(), required, family);
    }

    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter,
                                Map<String, String> fields, Map<String, String> separators, List<String> required,
                                FamilyColumns family) {
        this(id, marketplace, formats, delimiter, fields, Map.of(), separators, required, family);
    }

    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter,
                                Map<String, String> fields, Map<String, List<String>> multiFields,
                                Map<String, String> separators, List<String> required, FamilyColumns family) {
        this(id, marketplace, formats, delimiter, java.nio.charset.StandardCharsets.UTF_8, fields, multiFields, separators,
                required, family);
    }

    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter, Charset encoding,
                                Map<String, String> fields, Map<String, List<String>> multiFields,
                                Map<String, String> separators, List<String> required, FamilyColumns family) {
        this(id, marketplace, formats, delimiter, encoding, fields, multiFields, separators, required, family, null);
    }

    /** {@code idColumn} names the header whose cell becomes {@link ListingRecord#id()}; null for none. */
    public ConfiguredCsvAdapter(String id, String marketplace, Set<String> formats, char delimiter, Charset encoding,
                                Map<String, String> fields, Map<String, List<String>> multiFields,
                                Map<String, String> separators, List<String> required, FamilyColumns family,
                                String idColumn) {
        this.idColumn = idColumn;
        this.id = id;
        this.marketplace = marketplace;
        this.formats = Set.copyOf(formats);
        this.delimiter = delimiter;
        this.encoding = encoding;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        Map<String, List<String>> lists = new LinkedHashMap<>();
        multiFields.forEach((field, columns) -> lists.put(field, List.copyOf(columns)));
        this.multiFields = Collections.unmodifiableMap(lists);
        this.separators = Collections.unmodifiableMap(new LinkedHashMap<>(separators));
        this.required = List.copyOf(required);
        this.family = family;
    }

    @Override public String id() { return id; }
    @Override public String marketplace() { return marketplace; }
    @Override public Set<String> formats() { return formats; }

    /** The column delimiter of the format, so a caller can read a header the way {@link #open} will. */
    public char delimiter() { return delimiter; }

    @Override
    public Detection detect(List<String> header) {
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>(required);
        wanted.addAll(fields.values());
        multiFields.values().forEach(wanted::addAll);
        if (idColumn != null) wanted.add(idColumn);
        if (family != null) {
            wanted.add(family.roleColumn());
            wanted.add(family.skuColumn());
            wanted.add(family.parentSkuColumn());
        }
        List<String> missing = wanted.stream().filter(name -> !header.contains(name)).toList();
        List<String> ambiguous = wanted.stream().filter(name -> java.util.Collections.frequency(header, name) > 1).toList();
        return new Detection(missing.isEmpty() && ambiguous.isEmpty(), wanted.size() - missing.size(), missing, ambiguous);
    }

    @Override
    public ListingReader open(InputStream input, Limits limits) throws IOException {
        CountingInputStream bounded = new CountingInputStream(input, limits.maxBytes());
        var decoder = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(bounded, decoder));
        reader.mark(1);
        if (reader.read() != 0xfeff) reader.reset();
        MappingIterator<String[]> rows = CSV.readerFor(String[].class).with(CsvSchema.emptySchema().withColumnSeparator(delimiter))
                .readValues(reader);
        if (!rows.hasNext()) throw new IOException("Input has no header row");
        String[] names = rows.next();
        List<String> header = java.util.Arrays.stream(names).map(String::trim).toList();
        Detection detection = detect(header);
        if (!detection.supported()) throw new IOException("Input format has missing columns " + detection.missingRequiredColumns()
                + " and duplicate columns " + detection.ambiguousColumns());
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) indexes.put(header.get(index), index);
        return new ListingReader() {
            private long count;
            @Override public ListingRecord next() throws IOException {
                if (!rows.hasNext()) return null;
                if (++count > limits.maxRows()) throw new IOException("Input row limit exceeded");
                long line = rows.getCurrentLocation().getLineNr();
                String[] cells = rows.next();
                Map<String, Object> listing = new LinkedHashMap<>();
                List<FieldError> errors = new ArrayList<>();
                fields.forEach((field, column) -> {
                    Integer index = indexes.get(column);
                    if (index == null || index >= cells.length) {
                        errors.add(new FieldError(field, "missing-column", "Mapped column is missing from this row"));
                        return;
                    }
                    String value = cells[index].trim();
                    if (!value.isEmpty()) {
                        if (field.equals("price") || field.equals("quantity")) {
                            try {
                                listing.put(field, new BigDecimal(value));
                            } catch (NumberFormatException failure) {
                                errors.add(new FieldError(field, "invalid-number", "Value must be a plain decimal number"));
                            }
                        } else if (separators.containsKey(field)) {
                            listing.put(field, java.util.Arrays.stream(value.split(java.util.regex.Pattern.quote(separators.get(field)), -1))
                                    .map(String::trim).filter(part -> !part.isEmpty()).toList());
                        } else {
                            listing.put(field, value);
                        }
                    }
                });
                multiFields.forEach((field, columns) -> {
                    List<String> values = new ArrayList<>();
                    for (String column : columns) {
                        Integer index = indexes.get(column);
                        if (index == null || index >= cells.length) {
                            errors.add(new FieldError(field, "missing-column", "Mapped column is missing from this row"));
                        } else if (!cells[index].isBlank()) values.add(cells[index].trim());
                    }
                    if (!values.isEmpty()) listing.put(field, List.copyOf(values));
                });
                String familyId = null;
                String familyRole = null;
                if (family != null) {
                    String role = cell(cells, indexes.get(family.roleColumn()));
                    familyRole = role.equalsIgnoreCase(family.parentValue()) ? "parent"
                            : role.equalsIgnoreCase(family.childValue()) ? "child" : null;
                    if ("parent".equals(familyRole)) familyId = cell(cells, indexes.get(family.skuColumn()));
                    if ("child".equals(familyRole)) familyId = cell(cells, indexes.get(family.parentSkuColumn()));
                }
                String recordId = idColumn == null ? null : blankToNull(cell(cells, indexes.get(idColumn)));
                return new ListingRecord(line, listing, errors, blankToNull(familyId), familyRole, recordId);
            }
            @Override public void close() throws IOException { rows.close(); }
        };
    }

    private static String cell(String[] cells, Integer index) {
        return index == null || index >= cells.length ? "" : cells[index].trim();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static final class CountingInputStream extends FilterInputStream {
        private final long max;
        private long count;
        private CountingInputStream(InputStream input, long max) { super(input); this.max = max; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > max) throw new IOException("Input byte limit exceeded");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0 && (count += read) > max) throw new IOException("Input byte limit exceeded");
            return read;
        }
    }
}
