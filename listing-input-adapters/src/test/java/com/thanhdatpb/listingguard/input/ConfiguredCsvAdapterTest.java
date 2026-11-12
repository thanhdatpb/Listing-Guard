package com.thanhdatpb.listingguard.input;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredCsvAdapterTest {
    @Test
    void detectsHeadersAndStreamsBomSemicolonAndQuotedNewlines() throws Exception {
        var adapter = new ConfiguredCsvAdapter("demo", "ETSY", Set.of("text/csv"), ';',
                Map.of("title", "Title", "description", "Body"), List.of("Title", "Body"));
        assertThat(adapter.detect(List.of("Title", "Body", "Unused")).supported()).isTrue();
        assertThat(adapter.detect(List.of("Title")).missingRequiredColumns()).containsExactly("Body");
        assertThat(adapter.detect(List.of("Title", "Body", "Title")).ambiguousColumns()).containsExactly("Title");
        byte[] bytes = ("\ufeffTitle;Body;Other\r\nMug;\"line one\nline two\";ignored\r\n").getBytes(StandardCharsets.UTF_8);
        try (var reader = adapter.open(new ByteArrayInputStream(bytes), new ListingInputAdapter.Limits(1024, 1))) {
            var row = reader.next();
            assertThat(row.line()).isEqualTo(2);
            assertThat(row.listing()).containsEntry("title", "Mug").containsEntry("description", "line one\nline two")
                    .doesNotContainKey("Other");
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void enforcesRowAndByteLimitsAndRejectsMalformedEncoding() {
        var adapter = new ConfiguredCsvAdapter("demo", "ETSY", Set.of("text/csv"), ',',
                Map.of("title", "Title"), List.of("Title"));
        byte[] rows = "Title\na\nb\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> {
            try (var reader = adapter.open(new ByteArrayInputStream(rows), new ListingInputAdapter.Limits(1024, 1))) {
                reader.next();
                reader.next();
            }
        }).isInstanceOf(java.io.IOException.class).hasMessageContaining("row limit");
        assertThatThrownBy(() -> adapter.open(new ByteArrayInputStream(rows), new ListingInputAdapter.Limits(2, 1)))
                .isInstanceOf(java.io.IOException.class);
        byte[] invalid = {(byte) 0xc3, 0x28};
        assertThatThrownBy(() -> adapter.open(new ByteArrayInputStream(invalid), new ListingInputAdapter.Limits(20, 1)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void groupsAmazonParentAndChildRowsByFamilyKeyAndReturnsFieldErrors() throws Exception {
        var adapter = new ConfiguredCsvAdapter("amazon-flat-file", "AMAZON", Set.of("text/tab-separated-values"), '\t',
                Map.of("title", "item_name"), List.of("item_sku", "item_name"),
                new ConfiguredCsvAdapter.FamilyColumns("parentage", "parent", "child", "item_sku", "parent_sku"));
        byte[] file = ("item_sku\titem_name\tparentage\tparent_sku\n"
                + "P1\tFamily\tparent\t\nC1\tChild\tchild\tP1\n").getBytes(StandardCharsets.UTF_8);
        try (var reader = adapter.open(new ByteArrayInputStream(file), new ListingInputAdapter.Limits(2048, 2))) {
            var parent = reader.next();
            var child = reader.next();
            assertThat(parent.familyId()).isEqualTo("P1");
            assertThat(child.familyId()).isEqualTo(parent.familyId());
            assertThat(parent.familyRole()).isEqualTo("parent");
            assertThat(child.familyRole()).isEqualTo("child");
        }
        byte[] shortRow = "item_sku\titem_name\tparentage\tparent_sku\nP1\n".getBytes(StandardCharsets.UTF_8);
        try (var reader = adapter.open(new ByteArrayInputStream(shortRow), new ListingInputAdapter.Limits(2048, 2))) {
            assertThat(reader.next().errors()).extracting(ListingInputAdapter.FieldError::field).contains("title");
        }
    }

    @Test
    void loadsYamlTemplateAndAppliesItsMapping() throws Exception {
        try (var yaml = getClass().getResourceAsStream("/adapters/etsy-listings-export.yaml")) {
            var adapter = AdapterYamlLoader.loadCsv(yaml);
            byte[] file = "SKU,TITLE,DESCRIPTION,TAGS,MATERIALS,PRICE,QUANTITY\n"
                    .concat("s1,Mug,Handmade,ceramic,mud,12.5,4\n").getBytes(StandardCharsets.UTF_8);
            try (var reader = adapter.open(new ByteArrayInputStream(file), new ListingInputAdapter.Limits(1024, 2))) {
                var listing = reader.next().listing();
                assertThat(listing).containsEntry("title", "Mug").containsEntry("price", new java.math.BigDecimal("12.5"))
                        .containsEntry("quantity", new java.math.BigDecimal("4"))
                        .containsEntry("tags", List.of("ceramic"));
            }
        }
    }

    @Test
    void readsConfiguredLegacyEncodingWithoutReplacingBytes() throws Exception {
        var encoding = java.nio.charset.Charset.forName("windows-1252");
        var adapter = new ConfiguredCsvAdapter("legacy", "ETSY", Set.of("text/csv"), ',', encoding,
                Map.of("title", "Title"), Map.of(), Map.of(), List.of("Title"), null);
        byte[] file = "Title\nCafé\n".getBytes(encoding);
        try (var reader = adapter.open(new ByteArrayInputStream(file), new ListingInputAdapter.Limits(100, 1))) {
            assertThat(reader.next().listing()).containsEntry("title", "Café");
        }
    }
}
