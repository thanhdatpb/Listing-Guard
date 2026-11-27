package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Target;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the standard fields of one row from the columns a person confirmed (ADR-059). The result has the shape of the standard input
 * ({@code docs/input-schema.md}): text fields as strings, list fields as arrays, variation attributes in {@code attributes}. It only maps
 * and cleans (trim, drop blanks): it never dedupes, truncates, converts a price, or corrects a value (a whole quantity becomes the JSON number the standard input asks for), because a later check
 * must see what the seller wrote.
 */
final class StandardFields {

    /** One column of the sheet: the key its value is kept under, the header as written and the standard field it was given (or null). */
    record ColumnUse(String dataKey, String header, String target) {
    }

    /** What a row says: the standard fields and the few values that name or relate the row. */
    record Built(String sku, String parentSku, ListingRole explicitRole, String ownerEmail, ObjectNode fields) {

        String title() {
            return fields.hasNonNull("title") ? fields.get("title").asText() : null;
        }
    }

    private static final java.util.regex.Pattern WHOLE_NUMBER = java.util.regex.Pattern.compile("[+-]?\\d{1,18}");

    private final StandardTargets targets;
    private final ObjectMapper mapper;

    StandardFields(StandardTargets targets, ObjectMapper mapper) {
        this.targets = targets;
        this.mapper = mapper;
    }

    Built build(Marketplace marketplace, List<ColumnUse> columns, Map<String, String> data, Map<String, String> separators) {
        ObjectNode fields = mapper.createObjectNode();
        ObjectNode attributes = mapper.createObjectNode();
        Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
        Map<String, List<String>> joined = new java.util.LinkedHashMap<>();
        String sku = "";
        String parentSku = "";
        String owner = "";
        ListingRole role = null;
        for (ColumnUse column : columns) {
            if (column.target() == null || column.target().isEmpty()) {
                continue;
            }
            String value = data.getOrDefault(column.dataKey(), "").strip();
            Target target = targets.find(marketplace, column.target()).orElse(null);
            if (target == null || value.isEmpty()) {
                continue;
            }
            switch (target.kind()) {
                case SKU -> sku = value;
                case PARENT_SKU -> parentSku = value;
                case OWNER -> owner = value.toLowerCase(Locale.ROOT);
                case PARENT_CHILD -> {
                    if (targets.isParentValue(value)) {
                        role = ListingRole.PARENT;
                    } else if (targets.isChildValue(value)) {
                        role = ListingRole.CHILD;
                    }
                }
                case TEXT, PRICE, THEME -> fields.put(target.key(), value);
                // a quantity is a JSON number in the standard input and a string is not accepted (docs/input-schema.md): a whole number is written
                // as one, anything else stays the text the seller wrote, which the checks then report as missing
                case INTEGER -> {
                    if (WHOLE_NUMBER.matcher(value).matches()) {
                        // an int where it fits, so the node equals the one read back from the stored JSON
                        long number = Long.parseLong(value);
                        if (number == (int) number) {
                            fields.put(target.key(), (int) number);
                        } else {
                            fields.put(target.key(), number);
                        }
                    } else {
                        fields.put(target.key(), value);
                    }
                }
                case LIST -> {
                    String separator = separators.getOrDefault(target.key(), target.defaultSeparator());
                    List<String> entries = lists.computeIfAbsent(target.key(), k -> new ArrayList<>());
                    String cut = separator == null ? null : targets.separator(separator).orElse(null);
                    if (cut == null) {
                        entries.add(value);
                    } else {
                        for (String part : value.split(java.util.regex.Pattern.quote(cut), -1)) {
                            if (!part.strip().isEmpty()) {
                                entries.add(part.strip());
                            }
                        }
                    }
                }
                case TEXT_OR_LIST -> joined.computeIfAbsent(target.key(), k -> new ArrayList<>()).add(value);
                case ATTRIBUTE -> attributes.put(column.header().strip().isEmpty() ? column.dataKey() : column.header().strip(), value);
            }
        }
        lists.forEach((key, entries) -> {
            if (!entries.isEmpty()) {
                ArrayNode array = fields.putArray(key);
                entries.forEach(array::add);
            }
        });
        joined.forEach((key, parts) -> fields.put(key, String.join(" ", parts)));
        if (!attributes.isEmpty()) {
            fields.set("attributes", attributes);
        }
        return new Built(sku, parentSku, role, owner, fields);
    }
}
