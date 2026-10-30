package com.thanhdatpb.listingguard.api.batch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The mapping file of a batch: which CSV column feeds which field of the standard input
 * ({@code docs/input-schema.md}). It belongs to whoever runs the batch, so it has no version; it is checked completely
 * before the first row is read.
 *
 * <pre>
 * csv:                          # optional
 *   delimiter: ","              # one character, or "tab"; default ","
 *   encoding: UTF-8             # default UTF-8; a byte order mark is always dropped
 *   skip_lines: 0               # lines to ignore before the header row; default 0
 * id: sku                       # the column that names a listing in the reports (required)
 * title: Title                  # a text field: one column
 * quantity: Quantity            # a number: one column
 * bullet_points: [b1, b2, b3]   # a list: one entry per column ...
 * tags: {column: Tags, separator: ","}   # ... or one column cut at a separator
 * search_terms: Terms           # search_terms (Amazon) takes a column, several columns or a separator
 * </pre>
 *
 * Every key but {@code id} and {@code csv} must be a field of the input schema of the marketplace being run, so a
 * misspelt or foreign field fails at start-up instead of being silently ignored by the API's normalizer.
 */
public final class BatchMapping {

    /** What a field holds, which decides the forms its mapping may take. */
    enum Kind {
        /** one column, read as a string */
        TEXT,
        /** several columns, or one column cut at a separator */
        LIST,
        /** a string, or a list (Amazon {@code search_terms}) */
        TEXT_OR_LIST,
        /** one column, read as a number */
        INTEGER,
        /** one column, read as a plain decimal number */
        PRICE
    }

    /** The fields of the Etsy standard input; a test compares them with {@code docs/input-schema.md}. */
    private static final Map<String, Kind> ETSY = Map.ofEntries(
            Map.entry("title", Kind.TEXT), Map.entry("description", Kind.TEXT), Map.entry("tags", Kind.LIST),
            Map.entry("price", Kind.PRICE), Map.entry("category", Kind.TEXT), Map.entry("taxonomy_path", Kind.LIST),
            Map.entry("materials", Kind.LIST), Map.entry("images", Kind.LIST), Map.entry("quantity", Kind.INTEGER),
            Map.entry("who_made", Kind.TEXT), Map.entry("when_made", Kind.TEXT));

    /** The fields of the Amazon standard input; a test compares them with {@code docs/input-schema.md}. */
    private static final Map<String, Kind> AMAZON = Map.ofEntries(
            Map.entry("title", Kind.TEXT), Map.entry("brand", Kind.TEXT), Map.entry("bullet_points", Kind.LIST),
            Map.entry("description", Kind.TEXT), Map.entry("search_terms", Kind.TEXT_OR_LIST),
            Map.entry("product_type", Kind.TEXT), Map.entry("price", Kind.PRICE), Map.entry("quantity", Kind.INTEGER),
            Map.entry("images", Kind.LIST));

    /** The reader settings of the input file. */
    public record Csv(char delimiter, Charset encoding, int skipLines) {

        public static final Csv DEFAULT = new Csv(',', StandardCharsets.UTF_8, 0);
    }

    private enum Shape { SINGLE, MANY, SPLIT }

    /** Where a field reads from. {@code separator} is set for {@link Shape#SPLIT} only. */
    private record Source(Shape shape, List<String> columns, String separator) {
    }

    /** One problem with the data of one row; the batch records it and goes on. The message is Vietnamese (it is reported). */
    static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message, null, false, false);
        }
    }

    /** What one data row gives: the id that names it, its label when the batch has one, and the listing JSON. */
    record RowData(String id, Boolean label, ObjectNode listing) {
    }

    private static final int HEADER_NAMES_SHOWN = 40;
    private static final int CELL_SHOWN = 40;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final String marketplace;
    private final Csv csv;
    private final String idColumn;
    private final Map<String, Source> sources;

    private BatchMapping(String marketplace, Csv csv, String idColumn, Map<String, Source> sources) {
        this.marketplace = marketplace;
        this.csv = csv;
        this.idColumn = idColumn;
        this.sources = sources;
    }

    /** The input-schema fields of a marketplace with their kinds. */
    static Map<String, Kind> schema(String marketplace) {
        return switch (marketplace.trim().toUpperCase(Locale.ROOT)) {
            case "ETSY" -> ETSY;
            case "AMAZON" -> AMAZON;
            default -> throw new BatchConfigException("Batch checks support ETSY and AMAZON, not '" + marketplace + "'.");
        };
    }

    /** @throws BatchConfigException if the file cannot be read or does not say what a mapping must say */
    public static BatchMapping load(Path file, String marketplace) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BatchConfigException("Cannot read the mapping file " + file + ": " + e.getMessage(), e);
        }
        try {
            return parse(text, marketplace);
        } catch (BatchConfigException e) {
            throw new BatchConfigException("Mapping " + file + ": " + e.getMessage(), e);
        }
    }

    /** @throws BatchConfigException if the YAML is invalid or does not fit the input schema of the marketplace */
    public static BatchMapping parse(String yaml, String marketplace) {
        Map<String, Kind> schema = schema(marketplace);
        String code = marketplace.trim().toUpperCase(Locale.ROOT);
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new BatchConfigException("not valid YAML: " + firstLine(e.getMessage()), e);
        }
        if (root == null || !root.isObject()) {
            throw new BatchConfigException("it must be a YAML mapping with the key 'id' and the fields to read.");
        }
        Csv csv = Csv.DEFAULT;
        String idColumn = null;
        Map<String, Source> sources = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = root.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String key = entry.getKey();
            switch (key) {
                case "csv" -> csv = parseCsv(entry.getValue());
                case "id" -> idColumn = column("id", entry.getValue());
                default -> {
                    Kind kind = schema.get(key);
                    if (kind == null) {
                        throw new BatchConfigException("'" + key + "' is not a field of the " + code
                                + " input schema (docs/input-schema.md). Fields: " + new TreeSet<>(schema.keySet())
                                + "; besides them only 'id' and 'csv' are accepted.");
                    }
                    sources.put(key, parseSource(key, kind, entry.getValue()));
                }
            }
        }
        if (idColumn == null) {
            throw new BatchConfigException("'id' is required: name the column that identifies a listing "
                    + "(SKU, ASIN or listing id), for example  id: sku");
        }
        if (sources.isEmpty()) {
            throw new BatchConfigException("it maps no field: add at least one of " + new TreeSet<>(schema.keySet()));
        }
        // sorted by field, so the columns named in a message come in the same order on every run
        return new BatchMapping(code, csv, idColumn, Collections.unmodifiableMap(sources));
    }

    public Csv csv() {
        return csv;
    }

    /**
     * Checks the mapping against the header of the input file and fixes the column positions.
     *
     * @param header      the header names, trimmed, in file order
     * @param labelColumn the column that holds a takedown label, or null
     * @throws BatchConfigException if a column the mapping names is missing from the header or is ambiguous
     */
    public Bound bind(List<String> header, String labelColumn) {
        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            positions.computeIfAbsent(header.get(i), name -> new ArrayList<>()).add(i);
        }
        List<String> wanted = new ArrayList<>();
        wanted.add(idColumn);
        sources.values().forEach(source -> wanted.addAll(source.columns()));
        if (labelColumn != null) {
            wanted.add(labelColumn);
        }
        List<String> missing = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (String name : wanted) {
            List<Integer> at = positions.get(name);
            if (at == null) {
                if (!missing.contains(name)) {
                    missing.add(name);
                }
            } else if (at.size() > 1) {
                if (!ambiguous.contains(name)) {
                    ambiguous.add(name);
                }
            } else {
                index.put(name, at.get(0));
            }
        }
        if (!missing.isEmpty()) {
            throw new BatchConfigException("the header of the input file has no column " + quoted(missing)
                    + ". Columns are matched exactly (case and spaces matter). The header has: " + headerNames(header) + ".");
        }
        if (!ambiguous.isEmpty()) {
            throw new BatchConfigException("the header of the input file has the column " + quoted(ambiguous)
                    + " more than once, so the mapping cannot tell which one to read.");
        }
        Map<String, Kind> schema = schema(marketplace);
        Map<String, Bound.Binding> bindings = new TreeMap<>();
        sources.forEach((field, source) -> bindings.put(field, new Bound.Binding(schema.get(field), source.shape(),
                source.columns().stream().mapToInt(index::get).toArray(), source.separator(), source.columns().get(0))));
        return new Bound(header.size(), index.get(idColumn), idColumn, labelColumn == null ? -1 : index.get(labelColumn),
                labelColumn, bindings);
    }

    /** A mapping fixed to the columns of one input file; turns a row of cells into a listing. */
    public static final class Bound {

        private record Binding(Kind kind, Shape shape, int[] columns, String separator, String firstColumnName) {
        }

        private static final Set<String> TRUE_LABELS = Set.of("true", "1", "yes");
        private static final Set<String> FALSE_LABELS = Set.of("false", "0", "no");

        private final int columnCount;
        private final int idIndex;
        private final String idName;
        private final int labelIndex;
        private final String labelName;
        private final Map<String, Binding> bindings;

        private Bound(int columnCount, int idIndex, String idName, int labelIndex, String labelName,
                      Map<String, Binding> bindings) {
            this.columnCount = columnCount;
            this.idIndex = idIndex;
            this.idName = idName;
            this.labelIndex = labelIndex;
            this.labelName = labelName;
            this.bindings = bindings;
        }

        /** The id of a row whose data could not be read, so the report can still name it; null when there is none. */
        String idOrNull(String[] cells) {
            if (idIndex >= cells.length) {
                return null;
            }
            String id = cells[idIndex].trim();
            return id.isEmpty() ? null : id;
        }

        /**
         * @throws RowException if the row cannot be read: a wrong number of cells, an empty id, an unreadable number or
         *                      an unreadable label. A blank cell is not an error: the field is absent, and the rules
         *                      say whether that is a problem.
         */
        RowData read(String[] cells) {
            if (cells.length != columnCount) {
                throw new RowException("Dòng có " + cells.length + " ô nhưng header có " + columnCount
                        + " cột (thiếu cột, hoặc dấu phân cách nằm trong một giá trị không có dấu nháy).");
            }
            List<String> problems = new ArrayList<>();
            String id = cells[idIndex].trim();
            if (id.isEmpty()) {
                problems.add("cột id '" + idName + "' để trống");
            }
            Boolean label = null;
            if (labelIndex >= 0) {
                String text = cells[labelIndex].trim().toLowerCase(Locale.ROOT);
                if (TRUE_LABELS.contains(text)) {
                    label = Boolean.TRUE;
                } else if (FALSE_LABELS.contains(text)) {
                    label = Boolean.FALSE;
                } else if (!text.isEmpty()) {
                    problems.add("nhãn '" + labelName + "' = '" + clip(cells[labelIndex].trim())
                            + "' không hợp lệ (dùng true/false, 1/0 hoặc yes/no, hoặc để trống)");
                }
            }
            ObjectNode listing = JSON.objectNode();
            bindings.forEach((field, binding) -> put(listing, field, binding, cells, problems));
            if (!problems.isEmpty()) {
                throw new RowException("Dữ liệu sai: " + String.join("; ", problems) + ".");
            }
            return new RowData(id, label, listing);
        }

        private static void put(ObjectNode listing, String field, Binding binding, String[] cells, List<String> problems) {
            switch (binding.kind()) {
                case TEXT -> putText(listing, field, cells[binding.columns()[0]]);
                case INTEGER, PRICE -> putNumber(listing, field, binding, cells[binding.columns()[0]], problems);
                case LIST -> listing.set(field, list(binding, cells));
                case TEXT_OR_LIST -> {
                    if (binding.shape() == Shape.SINGLE) {
                        putText(listing, field, cells[binding.columns()[0]]);
                    } else {
                        listing.set(field, list(binding, cells));
                    }
                }
            }
        }

        private static void putText(ObjectNode listing, String field, String cell) {
            String value = cell.trim();
            if (!value.isEmpty()) {
                listing.put(field, value);
            }
        }

        private static void putNumber(ObjectNode listing, String field, Binding binding, String cell,
                                      List<String> problems) {
            String value = cell.trim();
            if (value.isEmpty()) {
                return;
            }
            try {
                listing.put(field, new BigDecimal(value));
            } catch (NumberFormatException e) {
                problems.add(field + " (cột '" + binding.firstColumnName() + "') = '" + clip(value)
                        + "' không phải số (không dùng ký hiệu tiền tệ hay dấu phân cách hàng nghìn)");
            }
        }

        /** The cells as list entries, as written: the normalizer trims them and drops blanks, like for the API. */
        private static ArrayNode list(Binding binding, String[] cells) {
            ArrayNode out = JSON.arrayNode();
            if (binding.shape() == Shape.SPLIT) {
                for (String part : cells[binding.columns()[0]].split(Pattern.quote(binding.separator()), -1)) {
                    out.add(part);
                }
            } else {
                for (int column : binding.columns()) {
                    out.add(cells[column]);
                }
            }
            return out;
        }
    }

    // ---- parsing

    private static Csv parseCsv(JsonNode node) {
        if (!node.isObject()) {
            throw new BatchConfigException("'csv' must be a mapping with delimiter, encoding and skip_lines.");
        }
        char delimiter = Csv.DEFAULT.delimiter();
        Charset encoding = Csv.DEFAULT.encoding();
        int skipLines = Csv.DEFAULT.skipLines();
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "delimiter" -> delimiter = delimiter(value);
                case "encoding" -> encoding = encoding(value);
                case "skip_lines" -> {
                    if (!value.isInt() || value.intValue() < 0) {
                        throw new BatchConfigException("csv.skip_lines must be a whole number, 0 or more.");
                    }
                    skipLines = value.intValue();
                }
                default -> throw new BatchConfigException("csv." + entry.getKey()
                        + " is not known; the keys are delimiter, encoding and skip_lines.");
            }
        }
        return new Csv(delimiter, encoding, skipLines);
    }

    private static char delimiter(JsonNode value) {
        if (!value.isTextual()) {
            throw new BatchConfigException("csv.delimiter must be one character in quotes, for example \",\" or \";\", or tab.");
        }
        String text = value.textValue();
        if (text.equals("tab") || text.equals("\\t")) {
            return '\t';
        }
        if (text.length() != 1 || text.equals("\"") || text.equals("\n") || text.equals("\r")) {
            throw new BatchConfigException("csv.delimiter must be one character (not a quote or a line break), "
                    + "or tab; got '" + text + "'.");
        }
        return text.charAt(0);
    }

    private static Charset encoding(JsonNode value) {
        if (!value.isTextual()) {
            throw new BatchConfigException("csv.encoding must be a charset name such as UTF-8 or windows-1252.");
        }
        try {
            return Charset.forName(value.textValue().trim());
        } catch (IllegalArgumentException e) {
            throw new BatchConfigException("csv.encoding '" + value.textValue() + "' is not a charset this Java knows.", e);
        }
    }

    private static Source parseSource(String field, Kind kind, JsonNode node) {
        Source source;
        if (node.isTextual()) {
            source = new Source(Shape.SINGLE, List.of(columnName(field, node.textValue())), null);
        } else if (node.isArray()) {
            List<String> columns = new ArrayList<>();
            for (JsonNode item : node) {
                columns.add(column(field, item));
            }
            if (columns.isEmpty()) {
                throw new BatchConfigException(field + ": the list of columns is empty.");
            }
            source = new Source(Shape.MANY, List.copyOf(columns), null);
        } else if (node.isObject()) {
            source = parseObjectSource(field, node);
        } else {
            throw new BatchConfigException(field + ": give a column name, a list of column names, or "
                    + "{column: <name>, separator: \"<text>\"}. Put a column name that looks like a number in quotes.");
        }
        checkShape(field, kind, source.shape());
        return source;
    }

    private static Source parseObjectSource(String field, JsonNode node) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals("column") && !name.equals("separator")) {
                throw new BatchConfigException(field + "." + name + " is not known; the keys are column and separator.");
            }
        }
        JsonNode column = node.get("column");
        if (column == null) {
            throw new BatchConfigException(field + ": the mapping has no 'column'.");
        }
        String name = column(field, column);
        JsonNode separator = node.get("separator");
        if (separator == null) {
            return new Source(Shape.SINGLE, List.of(name), null);
        }
        if (!separator.isTextual() || separator.textValue().isEmpty()) {
            throw new BatchConfigException(field + ".separator must be a non-empty text; it is cut literally, not as a pattern.");
        }
        return new Source(Shape.SPLIT, List.of(name), separator.textValue());
    }

    private static void checkShape(String field, Kind kind, Shape shape) {
        switch (kind) {
            case TEXT, INTEGER, PRICE -> {
                if (shape != Shape.SINGLE) {
                    throw new BatchConfigException(field + " holds one value: give one column name, not a list or a separator.");
                }
            }
            case LIST -> {
                if (shape == Shape.SINGLE) {
                    throw new BatchConfigException(field + " is a list: give a list of columns, for example [c1, c2], or "
                            + "{column: <name>, separator: \",\"} to cut one column into entries.");
                }
            }
            case TEXT_OR_LIST -> {
            }
        }
    }

    private static String column(String field, JsonNode node) {
        if (!node.isTextual()) {
            throw new BatchConfigException(field + ": a column name must be text (put one that looks like a number in quotes).");
        }
        return columnName(field, node.textValue());
    }

    private static String columnName(String field, String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new BatchConfigException(field + ": a column name is empty.");
        }
        return trimmed;
    }

    // ---- messages

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    private static String headerNames(List<String> header) {
        String shown = quoted(header.stream().filter(name -> !name.isEmpty()).limit(HEADER_NAMES_SHOWN).toList());
        return header.size() > HEADER_NAMES_SHOWN ? shown + ", ... (" + header.size() + " columns)" : shown;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        return line.length() <= 300 ? line : line.substring(0, 300) + "...";
    }

    /** A value quoted in a report is cut so that one bad cell cannot fill it. */
    private static String clip(String value) {
        return value.length() <= CELL_SHOWN ? value : value.substring(0, CELL_SHOWN) + "...";
    }

    @Override
    public String toString() {
        return "BatchMapping[" + marketplace + ", id=" + idColumn + ", fields=" + sources.keySet() + "]";
    }
}
