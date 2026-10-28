package com.thanhdatpb.listingguard.api.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmazonNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AmazonNormalizer normalizer = new AmazonNormalizer();

    private Listing normalize(String json) throws Exception {
        return normalizer.normalize(mapper.readTree(json));
    }

    @Test
    void identifiesItselfAsAmazon() {
        assertThat(normalizer.marketplace()).isEqualTo("AMAZON");
    }

    @Test
    void mapsEveryField() throws Exception {
        Listing listing = normalize("""
                {
                  "title": "Hardside luggage",
                  "brand": "Roadster",
                  "bullet_points": ["Lightweight", "Spinner wheels"],
                  "description": "A durable suitcase.",
                  "search_terms": "carry on suitcase",
                  "product_type": "LUGGAGE",
                  "price": {"amount": 49.99, "currency_code": "usd"},
                  "quantity": 5,
                  "images": ["https://example.com/1.jpg", "https://example.com/2.jpg"]
                }
                """);

        assertThat(listing.title()).isEqualTo("Hardside luggage");
        assertThat(listing.brand()).isEqualTo("Roadster");
        assertThat(listing.bulletPoints()).containsExactly("Lightweight", "Spinner wheels");
        assertThat(listing.description()).isEqualTo("A durable suitcase.");
        assertThat(listing.searchTerms()).isEqualTo("carry on suitcase");
        assertThat(listing.productType()).isEqualTo("LUGGAGE");
        assertThat(listing.price().amount()).isEqualByComparingTo(new BigDecimal("49.99"));
        assertThat(listing.price().currency()).isEqualTo("USD");
        assertThat(listing.quantity()).isEqualTo(5);
        assertThat(listing.images()).containsExactly("https://example.com/1.jpg", "https://example.com/2.jpg");
    }

    @Test
    void anEmptyObjectGivesAnEmptyListing() throws Exception {
        Listing listing = normalize("{}");

        assertThat(listing.title()).isNull();
        assertThat(listing.brand()).isNull();
        assertThat(listing.description()).isNull();
        assertThat(listing.searchTerms()).isNull();
        assertThat(listing.productType()).isNull();
        assertThat(listing.price()).isNull();
        assertThat(listing.quantity()).isNull();
        assertThat(listing.bulletPoints()).isEmpty();
        assertThat(listing.images()).isEmpty();
    }

    @Test
    void trimsTextAndTurnsBlankIntoAbsent() throws Exception {
        Listing listing = normalize("""
                {"title": "  Hardside luggage  ", "brand": "   ", "description": "", "product_type": "  luggage ", "search_terms": " "}
                """);

        assertThat(listing.title()).isEqualTo("Hardside luggage");
        assertThat(listing.brand()).isNull();
        assertThat(listing.description()).isNull();
        assertThat(listing.searchTerms()).isNull();
        assertThat(listing.productType()).isEqualTo("luggage");
    }

    @Test
    void theProductTypeKeepsTheCaseItWasWrittenIn() throws Exception {
        assertThat(normalize("{\"product_type\": \"luggage\"}").productType()).isEqualTo("luggage");
    }

    @Test
    void bulletPointsAreTrimmedBlanksDroppedAndDuplicatesKept() throws Exception {
        Listing listing = normalize("{\"bullet_points\": [\" One \", \"\", \"  \", \"One\", \"Two\"]}");

        assertThat(listing.bulletPoints()).containsExactly("One", "One", "Two");
    }

    @Test
    void bulletPointsThatAreNotAnArrayAreIgnored() throws Exception {
        assertThat(normalize("{\"bullet_points\": \"One\"}").bulletPoints()).isEmpty();
        assertThat(normalize("{\"bullet_points\": null}").bulletPoints()).isEmpty();
    }

    @Test
    void searchTermsAsAnArrayAreJoinedBySingleSpaces() throws Exception {
        assertThat(normalize("{\"search_terms\": [\" carry on \", \"\", \"suitcase\"]}").searchTerms()).isEqualTo("carry on suitcase");
        assertThat(normalize("{\"search_terms\": []}").searchTerms()).isNull();
        assertThat(normalize("{\"search_terms\": [\" \"]}").searchTerms()).isNull();
    }

    @Test
    void aSearchTermsStringIsNotSplitOrChanged() throws Exception {
        assertThat(normalize("{\"search_terms\": \"carry  on,suitcase\"}").searchTerms()).isEqualTo("carry  on,suitcase");
    }

    @Test
    void priceCanBeAPlainNumberOrAnObject() throws Exception {
        assertThat(normalize("{\"price\": 19.5}").price().amount()).isEqualByComparingTo(new BigDecimal("19.5"));
        assertThat(normalize("{\"price\": 19.5}").price().currency()).isNull();
        assertThat(normalize("{\"price\": {\"amount\": \"7.25\", \"currency\": \"eur\"}}").price().currency()).isEqualTo("EUR");
        assertThat(normalize("{\"price\": {\"amount\": 1999, \"divisor\": 100, \"currency_code\": \"USD\"}}").price().amount())
                .isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(normalize("{\"price\": {\"amount\": \"abc\"}}").price()).isNull();
        assertThat(normalize("{\"price\": null}").price()).isNull();
    }

    @Test
    void quantityMustBeAnInteger() throws Exception {
        assertThat(normalize("{\"quantity\": 0}").quantity()).isZero();
        assertThat(normalize("{\"quantity\": \"5\"}").quantity()).isNull();
        assertThat(normalize("{\"quantity\": 3000000000}").quantity()).isNull();
        assertThat(normalize("{\"quantity\": null}").quantity()).isNull();
    }

    @Test
    void etsyOnlyFieldsAreNotMapped() throws Exception {
        Listing listing = normalize("{\"tags\": [\"a\"], \"who_made\": \"i_did\", \"taxonomy_path\": [\"x\"], \"materials\": [\"clay\"]}");

        assertThat(listing.tags()).isEmpty();
        assertThat(listing.whoMade()).isNull();
        assertThat(listing.category()).isNull();
        assertThat(listing.materials()).isEmpty();
    }

    @Test
    void aListingThatIsNotAnObjectIsRejected() {
        for (String json : new String[] {"[]", "\"text\"", "5", "null"}) {
            JsonNode node = parse(json);

            assertThatThrownBy(() -> normalizer.normalize(node)).as(json).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amazon listing must be a JSON object");
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
