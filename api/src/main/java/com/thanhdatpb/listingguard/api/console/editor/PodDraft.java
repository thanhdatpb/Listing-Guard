package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.variation.VariationCheckRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

record PodDraft(String profileKey, String variants, String parentSku, String variationTheme,
                String artworkWidth, String artworkHeight, Boolean personalizationConfigured, Boolean recheck) {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    record Row(String sku, String size, String color, String parentSku) { }

    PodDraft {
        variants = defaultText(variants);
        parentSku = defaultText(parentSku);
        variationTheme = defaultText(variationTheme);
        artworkWidth = defaultText(artworkWidth);
        artworkHeight = defaultText(artworkHeight);
        personalizationConfigured = Boolean.TRUE.equals(personalizationConfigured);
        recheck = Boolean.TRUE.equals(recheck);
    }

    List<Row> rows() {
        if (variants.length() > 6000 || parentSku.length() > 100 || variationTheme.length() > 64) {
            throw new IllegalArgumentException("POD variant input exceeds its limit.");
        }
        List<Row> rows = new ArrayList<>();
        for (String line : variants.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.strip().split("\\|", -1);
            if (rows.size() >= 30 || columns.length > 4) {
                throw new IllegalArgumentException("Invalid POD variant row.");
            }
            String[] fields = {"", "", "", ""};
            for (int index = 0; index < columns.length; index++) {
                fields[index] = columns[index].strip();
                if (fields[index].length() > 100) {
                    throw new IllegalArgumentException("POD variant field exceeds its limit.");
                }
            }
            rows.add(new Row(fields[0], fields[1], fields[2], fields[3]));
        }
        return List.copyOf(rows);
    }

    VariationCheckRequest variations(EditorMarketplace marketplace, String productType, List<Row> rows) {
        ObjectNode parent = JSON.objectNode();
        parent.put("sku", parentSku);
        parent.put("product_type", productType);
        parent.put("variation_theme", variationTheme);
        ArrayNode properties = JSON.arrayNode();
        if (rows.stream().anyMatch(row -> !row.size().isBlank())) {
            properties.add("Size");
        }
        if (rows.stream().anyMatch(row -> !row.color().isBlank())) {
            properties.add("Color");
        }
        ArrayNode children = JSON.arrayNode();
        for (Row row : rows) {
            ObjectNode child = children.addObject();
            child.put("sku", row.sku());
            child.put("parent_sku", row.parentSku());
            ObjectNode attributes = child.putObject("attributes").put("size", row.size()).put("color", row.color());
            if (marketplace.isAmazon() && "SizeName-ColorName".equalsIgnoreCase(variationTheme.trim())) {
                attributes.put("size_name", row.size()).put("color_name", row.color());
            }
        }
        return new VariationCheckRequest(marketplace.code(), parent, properties, children);
    }

    BigDecimal width() {
        return millimeters(artworkWidth);
    }

    BigDecimal height() {
        return millimeters(artworkHeight);
    }

    private static BigDecimal millimeters(String value) {
        if (value.isBlank()) {
            return null;
        }
        if (!value.matches("[0-9]{1,6}(\\.[0-9]{1,3})?")) {
            throw new IllegalArgumentException("Invalid POD artwork measurement.");
        }
        BigDecimal measurement = new BigDecimal(value);
        if (measurement.signum() == 0) {
            throw new IllegalArgumentException("POD artwork measurements must be positive.");
        }
        return measurement;
    }

    private static String defaultText(String value) {
        return value == null ? "" : value;
    }
}
