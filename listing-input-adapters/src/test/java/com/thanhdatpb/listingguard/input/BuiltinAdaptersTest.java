package com.thanhdatpb.listingguard.input;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinAdaptersTest {
    private static final ListingInputAdapter.Limits LIMITS = new ListingInputAdapter.Limits(4096, 10);

    @Test
    void readsAllCsvTemplatesAndKeepsAmazonFamilyRowsLinked() throws Exception {
        var adapters = BuiltinAdapterCatalog.adapters();
        assertThat(adapters).hasSize(5);

        var etsy = adapters.get(0);
        String etsyCsv = "SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS\ne1,Mug,Handmade,12.5,4,\"ceramic,gift\",clay\n";
        try (var rows = etsy.open(input(etsyCsv), LIMITS)) {
            assertThat(rows.next().listing()).containsEntry("title", "Mug").containsEntry("price", new java.math.BigDecimal("12.5"))
                    .containsEntry("tags", List.of("ceramic", "gift"));
        }

        var amazon = adapters.get(1);
        String amazonCsv = "item_sku\titem_name\tbrand_name\tproduct_description\tbullet_point1\tbullet_point2\tbullet_point3"
                + "\tbullet_point4\tbullet_point5\tstandard_price\tquantity\tgeneric_keywords\tfeed_product_type\tparentage\tparent_sku\n"
                + "p1\tFamily\tBrand\tDescription\tOne\tTwo\tThree\tFour\tFive\t9.99\t1\tterm\tTYPE\tparent\t\n"
                + "c1\tChild\tBrand\tDescription\tOne\tTwo\tThree\tFour\tFive\t9.99\t1\tterm\tTYPE\tchild\tp1\n";
        try (var rows = amazon.open(input(amazonCsv), LIMITS)) {
            var parent = rows.next();
            var child = rows.next();
            assertThat(parent.familyId()).isEqualTo("p1");
            assertThat(child.familyId()).isEqualTo(parent.familyId());
            assertThat(parent.listing().get("bullet_points")).isEqualTo(List.of("One", "Two", "Three", "Four", "Five"));
        }

        var shopify = adapters.get(2);
        String shopifyCsv = "Handle,Title,Body (HTML),Vendor,Type,Tags,Variant Price,Variant Inventory Qty\n"
                + "handle,Mug,Details,Vendor,Type,ceramic;gift,5.50,3\n";
        try (var rows = shopify.open(input(shopifyCsv), LIMITS)) {
            assertThat(rows.next().listing()).containsEntry("brand", "Vendor").containsEntry("product_type", "Type")
                    .containsEntry("search_terms", "ceramic;gift");
        }
    }

    @Test
    void everyCsvTemplateNamesItsRowsByTheIdColumnAndABlankIdIsNull() throws Exception {
        var adapters = BuiltinAdapterCatalog.adapters();
        try (var rows = adapters.get(0).open(input("SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS\ne-1,Mug,,,,,\n ,Cup,,,,,\n"),
                LIMITS)) {
            assertThat(rows.next().id()).isEqualTo("e-1");
            assertThat(rows.next().id()).isNull();
        }
        String amazonHeader = "item_sku\titem_name\tbrand_name\tproduct_description\tbullet_point1\tbullet_point2\tbullet_point3"
                + "\tbullet_point4\tbullet_point5\tstandard_price\tquantity\tgeneric_keywords\tfeed_product_type\tparentage\tparent_sku\n";
        try (var rows = adapters.get(1).open(input(amazonHeader + "a-1\tT\t\t\t\t\t\t\t\t\t\t\t\t\t\n"), LIMITS)) {
            assertThat(rows.next().id()).isEqualTo("a-1");
        }
        try (var rows = adapters.get(2).open(input("Handle,Title,Body (HTML),Vendor,Type,Tags,Variant Price,Variant Inventory Qty\n"
                + "s-1,Mug,,,,,1,1\n"), LIMITS)) {
            assertThat(rows.next().id()).isEqualTo("s-1");
        }
        assertThat(((ConfiguredCsvAdapter) adapters.get(0)).delimiter()).isEqualTo(',');
        assertThat(((ConfiguredCsvAdapter) adapters.get(1)).delimiter()).isEqualTo('\t');
    }

    @Test
    void aTemplateWithoutAnIdColumnGivesNoId() throws Exception {
        var adapter = new ConfiguredCsvAdapter("demo", "ETSY", java.util.Set.of("text/csv"), ',',
                java.util.Map.of("title", "T"), List.of("T"));
        try (var rows = adapter.open(input("T\nMug\n"), LIMITS)) {
            assertThat(rows.next().id()).isNull();
        }
    }

    @Test
    void genericJsonAdaptersAcceptBothStandardMarketplacesAndIgnoreUnknownFields() throws Exception {
        for (String marketplace : List.of("ETSY", "AMAZON")) {
            var adapter = adaptersFor(marketplace);
            try (var rows = adapter.open(input("[{\"title\":\"Synthetic\",\"unknown\":\"drop\"}]"), LIMITS)) {
                assertThat(rows.next().listing()).containsEntry("title", "Synthetic").doesNotContainKey("unknown");
            }
        }
    }

    private static ListingInputAdapter adaptersFor(String marketplace) {
        return BuiltinAdapterCatalog.adapters().stream().filter(adapter -> adapter.id().startsWith("generic-json")
                && adapter.marketplace().equals(marketplace)).findFirst().orElseThrow();
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
