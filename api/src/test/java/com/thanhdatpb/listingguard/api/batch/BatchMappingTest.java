package com.thanhdatpb.listingguard.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.listing.AmazonNormalizer;
import com.thanhdatpb.listingguard.api.listing.EtsyNormalizer;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mapping file: what it accepts, what it refuses at start-up, and how a row of cells becomes a listing. */
class BatchMappingTest {

    private static final Path INPUT_SCHEMA = Path.of("..", "docs", "input-schema.md");

    private static final String ETSY_MAPPING = """
            id: sku
            title: Title
            description: Description
            tags: {column: Tags, separator: ","}
            materials: [Mat1, Mat2]
            quantity: Qty
            price: Price
            category: Category
            who_made: WhoMade
            when_made: WhenMade
            """;

    private static final List<String> ETSY_HEADER = List.of("sku", "Title", "Description", "Tags", "Mat1", "Mat2", "Qty",
            "Price", "Category", "WhoMade", "WhenMade", "Takedown");

    private static final String AMAZON_MAPPING = """
            id: asin
            title: item_name
            brand: brand_name
            bullet_points: [bp1, bp2, bp3]
            search_terms: keywords
            product_type: ptype
            """;

    private static final List<String> AMAZON_HEADER = List.of("asin", "item_name", "brand_name", "bp1", "bp2", "bp3",
            "keywords", "ptype");

    private static BatchMapping.Bound bind(String yaml, String marketplace, List<String> header) {
        return BatchMapping.parse(yaml, marketplace).bind(header, null);
    }

    private static String[] cells(String... values) {
        return values;
    }

    // ---- a row becomes the listing the normalizer would read from the same JSON

    @Test
    void anEtsyRowIsReadFromColumnsAndFromACellCutAtASeparator() {
        BatchMapping.Bound bound = bind(ETSY_MAPPING, "ETSY", ETSY_HEADER);

        BatchMapping.RowData data = bound.read(cells("E-1", "Zorvex mug", "A mug, handmade", "mug, ceramic ,,gift", "clay", "",
                "5", "19.99", "Mugs", "i_did", "made_to_order", "true"));

        assertThat(data.id()).isEqualTo("E-1");
        assertThat(data.label()).isNull();
        Listing listing = new EtsyNormalizer().normalize(data.listing());
        assertThat(listing.title()).isEqualTo("Zorvex mug");
        assertThat(listing.description()).isEqualTo("A mug, handmade");
        assertThat(listing.tags()).containsExactly("mug", "ceramic", "gift");
        assertThat(listing.materials()).containsExactly("clay");
        assertThat(listing.quantity()).isEqualTo(5);
        assertThat(listing.price()).isEqualTo(new Money(new BigDecimal("19.99"), null));
        assertThat(listing.category()).isEqualTo("Mugs");
        assertThat(listing.whoMade()).isEqualTo("i_did");
        assertThat(listing.whenMade()).isEqualTo("made_to_order");
    }

    @Test
    void anAmazonRowIsReadFromSeveralColumnsForTheBulletsAndOneColumnForTheSearchTerms() {
        BatchMapping.Bound bound = bind(AMAZON_MAPPING, "AMAZON", AMAZON_HEADER);

        BatchMapping.RowData data = bound.read(cells("B0TEST0001", "Acme suitcase", "Acme", "light", "", "has wheels",
                "carry on luggage", "LUGGAGE"));

        Listing listing = new AmazonNormalizer().normalize(data.listing());
        assertThat(data.id()).isEqualTo("B0TEST0001");
        assertThat(listing.title()).isEqualTo("Acme suitcase");
        assertThat(listing.brand()).isEqualTo("Acme");
        assertThat(listing.bulletPoints()).containsExactly("light", "has wheels");
        assertThat(listing.searchTerms()).isEqualTo("carry on luggage");
        assertThat(listing.productType()).isEqualTo("LUGGAGE");
    }

    @Test
    void searchTermsMayAlsoBeSeveralColumnsOrOneCutColumn() {
        String header = "id,t1,t2,all";
        BatchMapping.Bound several = bind("id: id\nsearch_terms: [t1, t2]\n", "AMAZON", List.of(header.split(",")));
        BatchMapping.Bound cut = bind("id: id\nsearch_terms: {column: all, separator: \";\"}\n", "AMAZON",
                List.of(header.split(",")));

        assertThat(new AmazonNormalizer().normalize(several.read(cells("1", "red", "blue", "x;y")).listing()).searchTerms())
                .isEqualTo("red blue");
        assertThat(new AmazonNormalizer().normalize(cut.read(cells("1", "red", "blue", "x; y")).listing()).searchTerms())
                .isEqualTo("x y");
    }

    @Test
    void aBlankCellLeavesTheFieldAbsentAndIsNotAnError() {
        BatchMapping.Bound bound = bind(ETSY_MAPPING, "ETSY", ETSY_HEADER);

        BatchMapping.RowData data = bound.read(cells("E-2", "", "  ", "", "", "", "", "", "", "", "", ""));

        assertThat(data.listing().fieldNames()).toIterable().containsExactlyInAnyOrder("materials", "tags");
        Listing listing = new EtsyNormalizer().normalize(data.listing());
        assertThat(listing.title()).isNull();
        assertThat(listing.quantity()).isNull();
        assertThat(listing.price()).isNull();
    }

    @Test
    void aSeparatorIsCutLiterallyNotAsAPattern() {
        BatchMapping.Bound bound = bind("id: id\ntags: {column: tags, separator: \"|\"}\n", "ETSY", List.of("id", "tags"));

        Listing listing = new EtsyNormalizer().normalize(bound.read(cells("1", "a|b.c|d")).listing());

        assertThat(listing.tags()).containsExactly("a", "b.c", "d");
    }

    // ---- a row that cannot be read

    @Test
    void aRowWithAnotherNumberOfCellsThanTheHeaderIsRefusedWithBothNumbers() {
        BatchMapping.Bound bound = bind(ETSY_MAPPING, "ETSY", ETSY_HEADER);

        assertThatThrownBy(() -> bound.read(cells("E-3", "short row")))
                .isInstanceOf(BatchMapping.RowException.class).hasMessageContaining("2 ô").hasMessageContaining("12 cột");
    }

    @Test
    void anEmptyIdAnUnreadableNumberAndAnUnreadableLabelAreAllNamedInOneMessage() {
        BatchMapping mapping = BatchMapping.parse(ETSY_MAPPING, "ETSY");
        BatchMapping.Bound bound = mapping.bind(ETSY_HEADER, "Takedown");

        assertThatThrownBy(() -> bound.read(cells("", "t", "d", "a,b", "", "", "five", "$19.99", "c", "x", "y", "maybe")))
                .isInstanceOf(BatchMapping.RowException.class)
                .hasMessageContaining("cột id 'sku' để trống")
                .hasMessageContaining("quantity")
                .hasMessageContaining("'five'")
                .hasMessageContaining("price")
                .hasMessageContaining("'$19.99'")
                .hasMessageContaining("nhãn 'Takedown'")
                .hasMessageContaining("'maybe'");
    }

    @Test
    void theIdOfAnUnreadableRowIsStillAvailableWhenItsCellExists() {
        BatchMapping.Bound bound = bind(ETSY_MAPPING, "ETSY", ETSY_HEADER);

        assertThat(bound.idOrNull(cells("E-9", "short"))).isEqualTo("E-9");
        assertThat(bound.idOrNull(cells(" "))).isNull();
        assertThat(bound.idOrNull(cells())).isNull();
    }

    @Test
    void labelsAreReadAsTrueFalseOneZeroYesNoOrBlankIgnoringCase() {
        BatchMapping.Bound bound = BatchMapping.parse("id: id\ntitle: title\n", "ETSY")
                .bind(List.of("id", "title", "flag"), "flag");

        assertThat(bound.read(cells("1", "t", "TRUE")).label()).isTrue();
        assertThat(bound.read(cells("1", "t", " 1 ")).label()).isTrue();
        assertThat(bound.read(cells("1", "t", "Yes")).label()).isTrue();
        assertThat(bound.read(cells("1", "t", "false")).label()).isFalse();
        assertThat(bound.read(cells("1", "t", "0")).label()).isFalse();
        assertThat(bound.read(cells("1", "t", "no")).label()).isFalse();
        assertThat(bound.read(cells("1", "t", "")).label()).isNull();
    }

    // ---- a mapping that is refused before any row is read

    @Test
    void aFieldOfAnotherMarketplaceIsRefusedWithTheFieldsThatAreAllowed() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: tags\n", "AMAZON"))
                .isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("'tags' is not a field of the AMAZON input schema")
                .hasMessageContaining("bullet_points");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\nproduct_type: pt\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("'product_type' is not a field of the ETSY input schema");
    }

    @Test
    void aMisspeltFieldIsRefusedInsteadOfIgnored() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntitel: Title\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("'titel'");
    }

    @Test
    void theIdAndAtLeastOneFieldAreRequired() {
        assertThatThrownBy(() -> BatchMapping.parse("title: Title\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("'id' is required");
        assertThatThrownBy(() -> BatchMapping.parse("id: sku\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("maps no field");
        assertThatThrownBy(() -> BatchMapping.parse("", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("YAML mapping");
    }

    @Test
    void aFieldWhoseFormDoesNotMatchItsKindIsRefused() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntitle: [a, b]\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("title holds one value");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\nquantity: {column: q, separator: \",\"}\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("quantity holds one value");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: Tags\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("tags is a list");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: {column: Tags}\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("tags is a list");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: []\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("empty");
    }

    @Test
    void anObjectFormWithAnUnknownKeyOrAnEmptySeparatorIsRefused() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: {column: Tags, sep: \",\"}\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("tags.sep is not known");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: {column: Tags, separator: \"\"}\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("separator");
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntags: {separator: \",\"}\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no 'column'");
    }

    @Test
    void aColumnNameThatIsNotTextIsRefusedWithAHintToQuoteIt() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntitle: 2024\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("quotes");
    }

    @Test
    void aKeyWrittenTwiceIsRefusedInsteadOfTheLastOneWinning() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntitle: A\ntitle: B\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("not valid YAML");
    }

    @Test
    void theCsvSectionReadsTheDelimiterTheEncodingAndTheLinesToSkip() {
        assertThat(BatchMapping.parse("id: id\ntitle: t\n", "ETSY").csv()).isEqualTo(BatchMapping.Csv.DEFAULT);

        BatchMapping.Csv tab = BatchMapping.parse("csv: {delimiter: tab, encoding: windows-1252, skip_lines: 2}\nid: id\ntitle: t\n",
                "AMAZON").csv();
        assertThat(tab.delimiter()).isEqualTo('\t');
        assertThat(tab.encoding()).isEqualTo(Charset.forName("windows-1252"));
        assertThat(tab.skipLines()).isEqualTo(2);

        assertThat(BatchMapping.parse("csv: {delimiter: \";\"}\nid: id\ntitle: t\n", "ETSY").csv().delimiter()).isEqualTo(';');
        assertThat(BatchMapping.parse("csv: {delimiter: '\\t'}\nid: id\ntitle: t\n", "ETSY").csv().delimiter()).isEqualTo('\t');
    }

    @Test
    void aBadCsvSectionIsRefused() {
        assertThatThrownBy(() -> BatchMapping.parse("csv: {delimiter: \"ab\"}\nid: id\ntitle: t\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.delimiter");
        assertThatThrownBy(() -> BatchMapping.parse("csv: {delimiter: '\"'}\nid: id\ntitle: t\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.delimiter");
        assertThatThrownBy(() -> BatchMapping.parse("csv: {encoding: not-a-charset}\nid: id\ntitle: t\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.encoding");
        assertThatThrownBy(() -> BatchMapping.parse("csv: {skip_lines: -1}\nid: id\ntitle: t\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.skip_lines");
        assertThatThrownBy(() -> BatchMapping.parse("csv: {quote: x}\nid: id\ntitle: t\n", "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("csv.quote is not known");
    }

    @Test
    void aMarketplaceThatHasNoStandardInputIsRefused() {
        assertThatThrownBy(() -> BatchMapping.parse("id: id\ntitle: t\n", "WALMART"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("ETSY and AMAZON");
    }

    @Test
    void aMappingFileThatCannotBeReadNamesTheFile(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yaml");

        assertThatThrownBy(() -> BatchMapping.load(missing, "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("nope.yaml");
    }

    @Test
    void aProblemInAMappingFileNamesTheFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("bad.yaml"), "id: id\ntitel: Title\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> BatchMapping.load(file, "ETSY"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("bad.yaml").hasMessageContaining("'titel'");
    }

    // ---- the mapping against the header of the file

    @Test
    void aColumnThatIsMissingFromTheHeaderIsNamedWithTheColumnsThatExist() {
        BatchMapping mapping = BatchMapping.parse(ETSY_MAPPING, "ETSY");
        List<String> header = new ArrayList<>(ETSY_HEADER);
        header.remove("Tags");
        header.remove("Qty");

        assertThatThrownBy(() -> mapping.bind(header, null))
                .isInstanceOf(BatchConfigException.class)
                .hasMessageContaining("no column 'Qty', 'Tags'")
                .hasMessageContaining("matched exactly")
                .hasMessageContaining("'Description'");
    }

    @Test
    void columnsAreMatchedExactlyIncludingCase() {
        BatchMapping mapping = BatchMapping.parse("id: id\ntitle: Title\n", "ETSY");

        assertThatThrownBy(() -> mapping.bind(List.of("id", "title"), null))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no column 'Title'");
    }

    @Test
    void aColumnThatAppearsTwiceInTheHeaderIsAmbiguousOnlyWhenTheMappingNamesIt() {
        BatchMapping mapping = BatchMapping.parse("id: id\ntitle: title\n", "ETSY");

        assertThatThrownBy(() -> mapping.bind(List.of("id", "title", "title"), null))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("'title' more than once");
        assertThat(mapping.bind(List.of("id", "title", "other", "other"), null)).isNotNull();
    }

    @Test
    void theLabelColumnMustBeInTheHeaderToo() {
        BatchMapping mapping = BatchMapping.parse("id: id\ntitle: title\n", "ETSY");

        assertThatThrownBy(() -> mapping.bind(List.of("id", "title"), "takedown"))
                .isInstanceOf(BatchConfigException.class).hasMessageContaining("no column 'takedown'");
    }

    // ---- the mapping can only name fields the input schema has

    @Test
    void theFieldsOfEachMarketplaceAreTheOnesDocumentedInTheInputSchema() throws IOException {
        assertThat(BatchMapping.schema("ETSY").keySet()).containsExactlyInAnyOrderElementsOf(documentedFields("Etsy"));
        assertThat(BatchMapping.schema("AMAZON").keySet()).containsExactlyInAnyOrderElementsOf(documentedFields("Amazon"));
    }

    private static Set<String> documentedFields(String heading) throws IOException {
        String text = Files.readString(INPUT_SCHEMA);
        int start = text.indexOf("## " + heading);
        assertThat(start).as("heading '%s' in docs/input-schema.md", heading).isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        String section = text.substring(start, end < 0 ? text.length() : end);
        Set<String> fields = new TreeSet<>();
        Matcher row = Pattern.compile("^\\| `([a-z_]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (row.find()) {
            fields.add(row.group(1));
        }
        return fields;
    }
}
