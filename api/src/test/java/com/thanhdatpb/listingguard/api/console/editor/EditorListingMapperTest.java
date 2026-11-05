package com.thanhdatpb.listingguard.api.console.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.listing.AmazonNormalizer;
import com.thanhdatpb.listingguard.api.listing.EtsyNormalizer;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorListingMapperTest {

    /** Builds a form field by field; every field starts empty. */
    private static final class Form {
        private final EditorMarketplace marketplace;
        private String title = "";
        private String tags = "";
        private String description = "";
        private String bulletPoints = "";
        private String price = "";
        private String quantity = "";
        private String category = "";
        private String whoMade = "";
        private String whenMade = "";
        private String brand = "";
        private String searchTerms = "";
        private String productType = "";

        Form(EditorMarketplace marketplace) {
            this.marketplace = marketplace;
        }

        Form title(String v) { title = v; return this; }
        Form tags(String v) { tags = v; return this; }
        Form description(String v) { description = v; return this; }
        Form bulletPoints(String v) { bulletPoints = v; return this; }
        Form price(String v) { price = v; return this; }
        Form quantity(String v) { quantity = v; return this; }
        Form category(String v) { category = v; return this; }
        Form whoMade(String v) { whoMade = v; return this; }
        Form whenMade(String v) { whenMade = v; return this; }
        Form brand(String v) { brand = v; return this; }
        Form searchTerms(String v) { searchTerms = v; return this; }
        Form productType(String v) { productType = v; return this; }

        EditorForm build() {
            return new EditorForm(marketplace.code(), title, tags, description, bulletPoints, price, quantity, category,
                    whoMade, whenMade, brand, searchTerms, productType);
        }

        EditorListingMapper.Mapped map() {
            return EditorListingMapper.map(marketplace, build());
        }
    }

    private static Form etsy() {
        return new Form(EditorMarketplace.ETSY);
    }

    private static Form amazon() {
        return new Form(EditorMarketplace.AMAZON);
    }

    @Test
    void anEmptyFormMapsToAnEmptyObject() {
        EditorListingMapper.Mapped mapped = etsy().map();

        assertThat(mapped.valid()).isTrue();
        assertThat(mapped.listing().size()).isZero();
    }

    @Test
    void anEtsyFormMapsToTheFieldsOfTheInputSchema() {
        JsonNode listing = etsy().title("Synthetic mug").tags("one\r\ntwo\n\nthree").description("A synthetic description.")
                .price(" 19.99 ").quantity("5").category("Home").whoMade("i_did").whenMade("made_to_order")
                .map().listing();

        assertThat(listing.get("title").asText()).isEqualTo("Synthetic mug");
        assertThat(listing.get("description").asText()).isEqualTo("A synthetic description.");
        assertThat(listing.get("tags")).hasSize(4);
        assertThat(listing.get("tags").get(0).asText()).isEqualTo("one");
        assertThat(listing.get("tags").get(1).asText()).isEqualTo("two");
        assertThat(listing.get("tags").get(2).asText()).isEmpty();
        assertThat(listing.get("tags").get(3).asText()).isEqualTo("three");
        assertThat(listing.get("price").asText()).isEqualTo("19.99");
        assertThat(listing.get("quantity").isIntegralNumber()).isTrue();
        assertThat(listing.get("quantity").asLong()).isEqualTo(5L);
        assertThat(listing.get("category").asText()).isEqualTo("Home");
        assertThat(listing.get("who_made").asText()).isEqualTo("i_did");
        assertThat(listing.get("when_made").asText()).isEqualTo("made_to_order");
        assertThat(listing.has("brand")).isFalse();
        assertThat(listing.has("bullet_points")).isFalse();
    }

    @Test
    void textIsPassedOnAsTypedBecauseTheNormalizerTrims() {
        JsonNode listing = etsy().title("  Padded title  ").map().listing();

        assertThat(listing.get("title").asText()).isEqualTo("  Padded title  ");
    }

    @Test
    void blankFieldsAndAnAllBlankListAreLeftOut() {
        assertThat(etsy().title("   ").tags("\n  \n").category("  ").map().listing().size()).isZero();
    }

    @Test
    void anAmazonFormMapsItsOwnFieldsAndIgnoresTheEtsyOnesTheSellerTyped() {
        JsonNode listing = amazon().title("Synthetic case").brand("Acme").bulletPoints("light\nstrong")
                .searchTerms("carry on luggage").productType("LUGGAGE").tags("leftover etsy tag").whoMade("i_did")
                .map().listing();

        assertThat(listing.get("brand").asText()).isEqualTo("Acme");
        assertThat(listing.get("bullet_points")).hasSize(2);
        assertThat(listing.get("search_terms").asText()).isEqualTo("carry on luggage");
        assertThat(listing.get("product_type").asText()).isEqualTo("LUGGAGE");
        assertThat(listing.has("tags")).isFalse();
        assertThat(listing.has("who_made")).isFalse();
    }

    @Test
    void aPriceThatIsNotADecimalNumberIsRefusedForThatField() {
        for (String bad : List.of("19,99", "abc", "$5", "1 000")) {
            EditorListingMapper.Mapped mapped = etsy().price(bad).map();

            assertThat(mapped.valid()).as(bad).isFalse();
            assertThat(mapped.errors()).containsOnlyKeys("price");
            assertThat(mapped.listing().has("price")).as(bad).isFalse();
        }
    }

    @Test
    void aQuantityThatIsNotAWholeNumberIsRefusedForThatField() {
        for (String bad : List.of("5.5", "abc", "1e3")) {
            EditorListingMapper.Mapped mapped = etsy().quantity(bad).map();

            assertThat(mapped.valid()).as(bad).isFalse();
            assertThat(mapped.errors()).containsOnlyKeys("quantity");
            assertThat(mapped.listing().has("quantity")).as(bad).isFalse();
        }
    }

    @Test
    void anEmptyQuantityIsAbsentNotAnError() {
        EditorListingMapper.Mapped mapped = etsy().quantity("  ").map();

        assertThat(mapped.valid()).isTrue();
        assertThat(mapped.listing().has("quantity")).isFalse();
    }

    @Test
    void zeroAndNegativeNumbersPassThroughBecauseTheRulesDecideWhetherTheyAreAllowed() {
        EditorListingMapper.Mapped mapped = etsy().price("0").quantity("-3").map();

        assertThat(mapped.valid()).isTrue();
        assertThat(mapped.listing().get("price").asText()).isEqualTo("0");
        assertThat(mapped.listing().get("quantity").asLong()).isEqualTo(-3L);
    }

    @Test
    void theMappedJsonIsReadByTheSameNormalizersAsAnApiRequest() {
        Listing etsyListing = new EtsyNormalizer().normalize(
                etsy().title(" Synthetic mug ").tags(" a \n\n b ").price("19.99").quantity("5").map().listing());

        assertThat(etsyListing.title()).isEqualTo("Synthetic mug");
        assertThat(etsyListing.tags()).containsExactly("a", "b");
        assertThat(etsyListing.price()).isNotNull();
        assertThat(etsyListing.quantity()).isEqualTo(5);

        Listing amazonListing = new AmazonNormalizer().normalize(
                amazon().bulletPoints(" x \n\n y ").searchTerms(" one two ").map().listing());

        assertThat(amazonListing.bulletPoints()).containsExactly("x", "y");
        assertThat(amazonListing.searchTerms()).isEqualTo("one two");
    }

    @Test
    void marketplaceCodesAreMatchedExactlyAndNeverDefaulted() {
        assertThat(EditorMarketplace.parse("etsy")).contains(EditorMarketplace.ETSY);
        assertThat(EditorMarketplace.parse(" AMAZON ")).contains(EditorMarketplace.AMAZON);
        assertThat(EditorMarketplace.parse("WALMART")).isEmpty();
        assertThat(EditorMarketplace.parse("")).isEmpty();
        assertThat(EditorMarketplace.parse(null)).isEmpty();
    }
}
