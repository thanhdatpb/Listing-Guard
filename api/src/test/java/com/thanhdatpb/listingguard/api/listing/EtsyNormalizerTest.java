package com.thanhdatpb.listingguard.api.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EtsyNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EtsyNormalizer normalizer = new EtsyNormalizer();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void reportsEtsyMarketplaceCode() {
        assertThat(normalizer.marketplace()).isEqualTo("ETSY");
    }

    @Test
    void mapsAllFields() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {
                  "title": "Handmade Mug",
                  "description": "A mug.",
                  "tags": ["mug", "ceramic"],
                  "price": {"amount": 1999, "divisor": 100, "currency_code": "usd"},
                  "taxonomy_path": ["Home & Living", "Kitchen", "Mugs"],
                  "materials": ["clay", "glaze"],
                  "images": [{"url_fullxfull": "https://img/1.jpg"}, "https://img/2.jpg"],
                  "quantity": 5,
                  "who_made": "i_did",
                  "when_made": "made_to_order"
                }
                """));

        assertThat(listing.title()).isEqualTo("Handmade Mug");
        assertThat(listing.description()).isEqualTo("A mug.");
        assertThat(listing.tags()).containsExactly("mug", "ceramic");
        assertThat(listing.price().amount()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(listing.price().currency()).isEqualTo("USD");
        assertThat(listing.category()).isEqualTo("Home & Living > Kitchen > Mugs");
        assertThat(listing.materials()).containsExactly("clay", "glaze");
        assertThat(listing.images()).containsExactly("https://img/1.jpg", "https://img/2.jpg");
        assertThat(listing.quantity()).isEqualTo(5);
        assertThat(listing.whoMade()).isEqualTo("i_did");
        assertThat(listing.whenMade()).isEqualTo("made_to_order");
    }

    @Test
    void trimsValuesAndDropsBlankEntries() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"title": "  Mug  ", "description": "   ", "tags": [" a ", "", "  "], "materials": ["", " wood "]}
                """));

        assertThat(listing.title()).isEqualTo("Mug");
        assertThat(listing.description()).isNull();
        assertThat(listing.tags()).containsExactly("a");
        assertThat(listing.materials()).containsExactly("wood");
    }

    @Test
    void dedupesTagsIgnoringCaseKeepingFirstSpelling() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"tags": ["Mug", "mug", "MUG", "cup"]}
                """));

        assertThat(listing.tags()).containsExactly("Mug", "cup");
    }

    @Test
    void doesNotTruncateOrRejectOverlongValues() throws Exception {
        String longTitle = "x".repeat(500);
        Listing listing = normalizer.normalize(json("{\"title\": \"" + longTitle + "\"}"));

        assertThat(listing.title()).hasSize(500);
    }

    @Test
    void missingFieldsBecomeNullOrEmptyLists() throws Exception {
        Listing listing = normalizer.normalize(json("{}"));

        assertThat(listing.title()).isNull();
        assertThat(listing.description()).isNull();
        assertThat(listing.price()).isNull();
        assertThat(listing.category()).isNull();
        assertThat(listing.quantity()).isNull();
        assertThat(listing.tags()).isEmpty();
        assertThat(listing.materials()).isEmpty();
        assertThat(listing.images()).isEmpty();
    }

    @Test
    void acceptsPlainNumericPriceAndCategoryString() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"price": "12.50", "category": "Mugs"}
                """));

        assertThat(listing.price().amount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(listing.price().currency()).isNull();
        assertThat(listing.category()).isEqualTo("Mugs");
    }

    @Test
    void unparseablePriceBecomesNull() throws Exception {
        Listing listing = normalizer.normalize(json("""
                {"price": "free"}
                """));

        assertThat(listing.price()).isNull();
    }

    @Test
    void rejectsNonObjectPayload() throws Exception {
        assertThatThrownBy(() -> normalizer.normalize(json("[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
