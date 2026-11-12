package com.thanhdatpb.listingguard.input;

import java.util.List;
import java.util.Set;

/** The supported format templates and their stable public metadata. */
public final class BuiltinAdapterCatalog {
    private BuiltinAdapterCatalog() { }

    public static List<InputAdapterDescriptor> descriptors() {
        return List.of(
                new InputAdapterDescriptor("etsy-listings-export", "ETSY", Set.of("text/csv"),
                        List.of("SKU", "TITLE", "DESCRIPTION", "PRICE", "QUANTITY", "TAGS", "MATERIALS"), false, ""),
                new InputAdapterDescriptor("amazon-flat-file", "AMAZON", Set.of("text/csv", "text/tab-separated-values"),
                        List.of("item_sku", "item_name", "brand_name", "product_description", "bullet_point1", "bullet_point2",
                                "bullet_point3", "bullet_point4", "bullet_point5", "standard_price", "quantity", "generic_keywords",
                                "feed_product_type", "parentage", "parent_sku"), false, "amazon-parent-child"),
                new InputAdapterDescriptor("shopify-product-csv", "AMAZON", Set.of("text/csv"),
                        List.of("Handle", "Title", "Body (HTML)", "Vendor", "Type", "Tags", "Variant Price", "Variant Inventory Qty"), true, ""),
                new InputAdapterDescriptor("generic-json", "ETSY", Set.of("application/json"),
                        List.of(), false, ""),
                new InputAdapterDescriptor("generic-json-amazon", "AMAZON", Set.of("application/json"),
                        List.of(), false, "")
        );
    }

    /** Implementations used by file-reading clients; fields follow the bundled YAML templates. */
    public static List<ListingInputAdapter> adapters() {
        return List.of(
                csv("etsy-listings-export.yaml"),
                csv("amazon-flat-file.yaml"),
                csv("shopify-product-csv.yaml"),
                new GenericJsonAdapter("ETSY", Set.of("title", "description", "tags", "price", "category", "materials",
                        "images", "quantity", "who_made", "when_made")),
                new GenericJsonAdapter("AMAZON", Set.of("title", "brand", "bullet_points", "description", "search_terms",
                        "product_type", "price", "quantity", "images", "item_highlights"))
        );
    }

    private static ConfiguredCsvAdapter csv(String name) {
        try (var input = BuiltinAdapterCatalog.class.getResourceAsStream("/adapters/" + name)) {
            if (input == null) throw new IllegalStateException("Missing adapter template " + name);
            return AdapterYamlLoader.loadCsv(input);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Invalid built-in adapter template " + name, failure);
        }
    }
}
