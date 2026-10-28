package com.thanhdatpb.listingguard.api.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * docs/input-schema.md is a public contract. This test reads its examples and its field tables from the file and
 * holds them to the real normalizers, so the page cannot drift from the code unnoticed.
 */
class InputSchemaDocTest {

    private static final Path DOC = Path.of("..", "docs", "input-schema.md");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EtsyNormalizer etsy = new EtsyNormalizer();
    private final AmazonNormalizer amazon = new AmazonNormalizer();

    private static String doc() throws IOException {
        assertThat(DOC).as("docs/input-schema.md is missing").exists();
        return Files.readString(DOC);
    }

    /** The JSON in the fenced block whose info string is {@code json <name>}. */
    private static ObjectNode example(String name) throws IOException {
        Matcher block = Pattern.compile("```json " + name + "\\R(.*?)\\R```", Pattern.DOTALL).matcher(doc());
        assertThat(block.find()).as("example '%s' in docs/input-schema.md", name).isTrue();
        return (ObjectNode) MAPPER.readTree(block.group(1));
    }

    /** The field names in the first column of the table under the heading that starts with {@code heading}. */
    private static List<String> documentedFields(String heading) throws IOException {
        String text = doc();
        int start = text.indexOf("## " + heading);
        assertThat(start).as("heading '%s'", heading).isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        String section = text.substring(start, end < 0 ? text.length() : end);
        List<String> fields = new ArrayList<>();
        Matcher row = Pattern.compile("^\\| `([a-z_]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (row.find()) {
            fields.add(row.group(1));
        }
        return fields;
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ObjectNode without(ObjectNode node, String field) {
        ObjectNode copy = node.deepCopy();
        copy.remove(field);
        return copy;
    }

    @Test
    void theEtsyExampleIsReadAsDocumented() throws IOException {
        Listing listing = etsy.normalize(example("etsy-listing"));

        assertThat(listing.title()).isEqualTo("Handmade Ceramic Mug");
        assertThat(listing.description()).isEqualTo("A stoneware mug, glazed by hand.");
        assertThat(listing.tags()).containsExactly("mug", "ceramic", "handmade");
        assertThat(listing.price()).isEqualTo(new Money(new BigDecimal("19.99"), "USD"));
        assertThat(listing.category()).isEqualTo("Home & Living > Kitchen & Dining > Mugs");
        assertThat(listing.materials()).containsExactly("stoneware");
        assertThat(listing.images()).containsExactly("https://example.com/mug-1.jpg", "https://example.com/mug-2.jpg");
        assertThat(listing.quantity()).isEqualTo(5);
        assertThat(listing.whoMade()).isEqualTo("i_did");
        assertThat(listing.whenMade()).isEqualTo("made_to_order");
    }

    @Test
    void theAmazonExampleIsReadAsDocumented() throws IOException {
        Listing listing = amazon.normalize(example("amazon-listing"));

        assertThat(listing.title()).isEqualTo("Acme Carry-On Suitcase, Hard Shell, 20 Inch");
        assertThat(listing.brand()).isEqualTo("Acme");
        assertThat(listing.bulletPoints()).containsExactly("Lightweight hard shell", "360 degree spinner wheels", "TSA-approved lock");
        assertThat(listing.description()).isEqualTo("A carry-on suitcase for short trips.");
        assertThat(listing.searchTerms()).isEqualTo("carry on luggage travel");
        assertThat(listing.productType()).isEqualTo("LUGGAGE");
        assertThat(listing.price()).isEqualTo(new Money(new BigDecimal("89.99"), "USD"));
        assertThat(listing.quantity()).isEqualTo(12);
        assertThat(listing.images()).containsExactly("https://example.com/suitcase-1.jpg");
    }

    @Test
    void everyFieldOfTheEtsyExampleIsReadAndEveryDocumentedFieldIsInTheExampleOrTestedBelow() throws IOException {
        ObjectNode example = example("etsy-listing");
        Listing full = etsy.normalize(example);

        for (String field : fieldNames(example)) {
            assertThat(etsy.normalize(without(example, field))).as("Etsy field '%s' is read", field).isNotEqualTo(full);
        }
        List<String> documented = new ArrayList<>(documentedFields("Etsy"));
        documented.remove("category");
        assertThat(documented).containsExactlyInAnyOrderElementsOf(fieldNames(example));
    }

    @Test
    void everyFieldOfTheAmazonExampleIsReadAndEveryDocumentedFieldIsInTheExample() throws IOException {
        ObjectNode example = example("amazon-listing");
        Listing full = amazon.normalize(example);

        for (String field : fieldNames(example)) {
            assertThat(amazon.normalize(without(example, field))).as("Amazon field '%s' is read", field).isNotEqualTo(full);
        }
        assertThat(documentedFields("Amazon")).containsExactlyInAnyOrderElementsOf(fieldNames(example));
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        ObjectNode etsyExample = example("etsy-listing");
        ObjectNode amazonExample = example("amazon-listing");
        Listing etsyListing = etsy.normalize(etsyExample);
        Listing amazonListing = amazon.normalize(amazonExample);

        etsyExample.put("something_new", "x").set("nested", MAPPER.createObjectNode().put("a", 1));
        amazonExample.put("something_new", "x").put("tags", "ignored for Amazon");

        assertThat(etsy.normalize(etsyExample)).isEqualTo(etsyListing);
        assertThat(amazon.normalize(amazonExample)).isEqualTo(amazonListing);
    }

    @Test
    void theEtsyCategoryFieldTakesPrecedenceOverTheTaxonomyPath() throws IOException {
        ObjectNode example = example("etsy-listing");
        example.put("category", "Kitchen");

        assertThat(etsy.normalize(example).category()).isEqualTo("Kitchen");
    }

    @Test
    void thePriceFormsOfTheTableAreAllAccepted() throws Exception {
        assertThat(price("{\"amount\": 1999, \"divisor\": 100, \"currency_code\": \"USD\"}")).isEqualTo(new Money(new BigDecimal("19.99"), "USD"));
        assertThat(price("{\"amount\": \"19.99\", \"currency\": \"usd\"}")).isEqualTo(new Money(new BigDecimal("19.99"), "USD"));
        assertThat(price("19.99")).isEqualTo(new Money(new BigDecimal("19.99"), null));
        assertThat(price("\"not a number\"")).isNull();
    }

    private Money price(String json) throws Exception {
        JsonNode listing = MAPPER.readTree("{\"price\": " + json + "}");
        return etsy.normalize(listing).price();
    }
}
