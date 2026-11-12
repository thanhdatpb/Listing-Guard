package com.thanhdatpb.listingguard.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads a CSV adapter template from YAML without depending on Spring or the API module. */
public final class AdapterYamlLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private AdapterYamlLoader() { }

    public static ConfiguredCsvAdapter loadCsv(InputStream input) throws IOException {
        JsonNode root = YAML.readTree(input);
        String id = text(root, "id");
        String marketplace = text(root, "marketplace").toUpperCase(java.util.Locale.ROOT);
        if (!marketplace.equals("ETSY") && !marketplace.equals("AMAZON")) {
            throw new IOException("marketplace must be ETSY or AMAZON");
        }
        if (!text(root, "format").equalsIgnoreCase("csv")) throw new IOException("format must be csv");
        String delimiterText = text(root, "delimiter");
        char delimiter = delimiterText.equalsIgnoreCase("tab") || delimiterText.equals("\\t") ? '\t'
                : delimiterText.length() == 1 ? delimiterText.charAt(0) : '\0';
        if (delimiter == '\0' || delimiter == '"' || delimiter == '\r' || delimiter == '\n') {
            throw new IOException("delimiter must be one non-quote character or tab");
        }
        Charset encoding;
        try { encoding = Charset.forName(text(root, "encoding")); }
        catch (IllegalArgumentException failure) { throw new IOException("encoding is not supported", failure); }
        JsonNode requiredNode = root.path("required_columns");
        if (!requiredNode.isArray()) throw new IOException("required_columns must be an array");
        List<String> required = new ArrayList<>();
        requiredNode.forEach(value -> required.add(value.asText()));
        JsonNode fieldNode = root.path("fields");
        if (!fieldNode.isObject() || fieldNode.isEmpty()) throw new IOException("fields must be a non-empty mapping");
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, List<String>> multiFields = new LinkedHashMap<>();
        Map<String, String> separators = new LinkedHashMap<>();
        fieldNode.fields().forEachRemaining(entry -> {
            JsonNode source = entry.getValue();
            if (source.isTextual()) fields.put(entry.getKey(), source.textValue());
            else if (source.isArray()) {
                List<String> columns = new ArrayList<>();
                source.forEach(column -> columns.add(column.asText()));
                if (columns.isEmpty()) throw new IllegalArgumentException("field mapping for " + entry.getKey() + " is empty");
                multiFields.put(entry.getKey(), columns);
            }
            else if (source.isObject() && source.path("column").isTextual()) {
                fields.put(entry.getKey(), source.path("column").textValue());
                if (source.path("separator").isTextual()) separators.put(entry.getKey(), source.path("separator").textValue());
            } else throw new IllegalArgumentException("field mapping for " + entry.getKey() + " must name a column");
        });
        ConfiguredCsvAdapter.FamilyColumns family = null;
        JsonNode familyNode = root.path("family");
        if (familyNode.isObject()) {
            family = new ConfiguredCsvAdapter.FamilyColumns(text(familyNode, "role_column"), text(familyNode, "parent_value"),
                    text(familyNode, "child_value"), text(familyNode, "sku_column"), text(familyNode, "parent_sku_column"));
        }
        Set<String> formats = delimiter == '\t' ? Set.of("text/csv", "text/tab-separated-values") : Set.of("text/csv");
        return new ConfiguredCsvAdapter(id, marketplace, formats, delimiter, encoding, fields, multiFields, separators,
                required, family);
    }

    private static String text(JsonNode node, String name) throws IOException {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IOException(name + " must be non-empty text");
        return value.textValue();
    }
}
