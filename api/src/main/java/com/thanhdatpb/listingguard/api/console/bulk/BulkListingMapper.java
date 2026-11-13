package com.thanhdatpb.listingguard.api.console.bulk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.bulk.BulkTargets.Target;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.input.StandardListingAdapter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the cells of one row into the standard listing JSON of {@code docs/input-schema.md} (ADR-029), the same object a client sends
 * to {@code POST /api/v1/checks}, and picks out the three things that are not listing fields: the SKU, the parent SKU and the
 * variation attributes.
 *
 * <p>It only maps. A blank cell is an absent field (the rules report it as missing); a value is passed on as written, because the
 * normalizer trims and the rules must see what the seller wrote. The two numbers are the exception, as in the editor and in
 * {@code batch-check}: a price that is not a plain decimal number, or a quantity that is not a whole number, makes the row unreadable
 * instead of being dropped (which would be reported as "missing"). Nothing is guessed: no currency sign is removed and no decimal
 * comma is read.
 */
final class BulkListingMapper {

    /**
     * @param blank true when every cell of the row is blank; the row is skipped
     * @param error why the row cannot be read (Vietnamese), or null
     */
    record Mapped(String sku, String parentSku, String theme, Map<String, String> attributes, ObjectNode listing, String error,
                  boolean blank) {
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?\\d{1,18}");
    private static final int CELL_SHOWN = 40;

    private BulkListingMapper() {
    }

    static Mapped map(EditorMarketplace marketplace, ColumnMapping mapping, List<String> labels, List<String> cells) {
        if (cells.stream().allMatch(String::isBlank)) {
            return new Mapped(null, null, null, Map.of(), JSON.objectNode(), null, true);
        }
        ObjectNode listing = JSON.objectNode();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        String sku = null;
        String parentSku = null;
        String theme = null;
        for (int i = 0; i < labels.size(); i++) {
            String key = mapping.targetOf(i);
            if (key.isEmpty()) {
                continue;
            }
            Target target = BulkTargets.find(marketplace, key).orElse(null);
            if (target == null) {
                continue;
            }
            String cell = i < cells.size() ? cells.get(i) : "";
            switch (target.kind()) {
                case SKU -> sku = sku != null || cell.isBlank() ? sku : cell.trim();
                case PARENT_SKU -> parentSku = parentSku != null || cell.isBlank() ? parentSku : cell.trim();
                case THEME -> theme = theme != null || cell.isBlank() ? theme : cell.trim();
                case ATTRIBUTE -> {
                    if (!cell.isBlank()) {
                        attributes.putIfAbsent(labels.get(i), cell.trim());
                    }
                }
                case TEXT -> {
                    if (!cell.isBlank()) {
                        listing.put(key, cell);
                    }
                }
                case LIST, TEXT_OR_LIST -> lists.computeIfAbsent(key, k -> new ArrayList<>()).add(cell);
                case PRICE -> {
                    String value = cell.trim();
                    if (value.isEmpty()) {
                        break;
                    }
                    if (PLAIN_DECIMAL.matcher(value).matches()) {
                        listing.put(key, new BigDecimal(value));
                    } else {
                        problems.add("Giá (cột “" + labels.get(i) + "”) = “" + clip(value) + "” không phải số thập phân "
                                + "thường: dùng dấu chấm, không có ký hiệu tiền tệ hay dấu phân cách hàng nghìn");
                    }
                }
                case INTEGER -> {
                    String value = cell.trim();
                    if (value.isEmpty()) {
                        break;
                    }
                    if (WHOLE_NUMBER.matcher(value).matches()) {
                        listing.put(key, Long.parseLong(value));
                    } else {
                        problems.add("Số lượng (cột “" + labels.get(i) + "”) = “" + clip(value)
                                + "” không phải số nguyên");
                    }
                }
            }
        }
        lists.forEach((key, columns) -> putList(listing, marketplace, mapping, key, columns));
        ObjectNode standard = StandardListingAdapter.toStandardInput(marketplace.code(), listing);
        String error = problems.isEmpty() ? null : "Dữ liệu sai: " + String.join("; ", problems) + ".";
        return new Mapped(sku, parentSku, theme, attributes, standard, error, false);
    }

    private static void putList(ObjectNode listing, EditorMarketplace marketplace, ColumnMapping mapping, String key,
                                List<String> columns) {
        Target target = BulkTargets.find(marketplace, key).orElseThrow();
        List<String> entries;
        if (columns.size() > 1) {
            entries = columns;
        } else if (target.kind() == BulkTargets.Kind.TEXT_OR_LIST) {
            if (!columns.get(0).isBlank()) {
                listing.put(key, columns.get(0));
            }
            return;
        } else {
            entries = split(columns.get(0), BulkTargets.separator(mapping.separatorOf(target)).orElseThrow().text());
        }
        if (entries.stream().allMatch(String::isBlank)) {
            return;
        }
        ArrayNode array = listing.putArray(key);
        entries.forEach(array::add);
    }

    /** One entry per piece, kept as written (the normalizer trims and drops blanks); a line break is {@code \r\n}, {@code \n} or {@code \r}. */
    static List<String> split(String text, String separator) {
        if (text.isBlank()) {
            return List.of();
        }
        if ("\n".equals(separator)) {
            return List.of(text.split("\\R", -1));
        }
        return List.of(text.split(Pattern.quote(separator), -1));
    }

    private static String clip(String value) {
        return value.length() <= CELL_SHOWN ? value : value.substring(0, CELL_SHOWN) + "...";
    }
}
