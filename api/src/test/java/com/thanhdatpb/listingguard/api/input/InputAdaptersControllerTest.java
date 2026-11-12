package com.thanhdatpb.listingguard.api.input;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputAdaptersControllerTest {
    @Test
    void exposesAdapterFormatsColumnsExperimentalStateAndFamilyMetadata() {
        var response = new InputAdaptersController().adapters();
        assertThat(response.adapters()).extracting(InputAdaptersController.AdapterView::id)
                .contains("etsy-listings-export", "amazon-flat-file", "shopify-product-csv", "generic-json");
        var amazon = response.adapters().stream().filter(adapter -> adapter.id().equals("amazon-flat-file")).findFirst().orElseThrow();
        assertThat(amazon.family()).isEqualTo("amazon-parent-child");
        var shopify = response.adapters().stream().filter(adapter -> adapter.id().equals("shopify-product-csv")).findFirst().orElseThrow();
        assertThat(shopify.marketplace()).isEqualTo("AMAZON");
        assertThat(shopify.experimental()).isTrue();
        assertThat(shopify.requiredColumns()).contains("Handle", "Title", "Variant Price");
    }
}
