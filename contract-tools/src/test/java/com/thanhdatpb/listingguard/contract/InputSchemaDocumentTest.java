package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/api/input-schema.json} is the machine-readable form of {@code docs/input-schema.md} (ADR-022, section 9): every
 * field table of the page is a definition with the same fields and a compatible type, every definition is covered, the schema is
 * a valid draft 2020-12 schema, and every JSON example of the page is valid against it. The page itself is held to the
 * normalizers by the tests of the {@code api} module, so the schema cannot drift from the code without one of them failing.
 */
class InputSchemaDocumentTest {

    private static final Path PAGE = Path.of("..", "docs", "input-schema.md");
    private static final Path SCHEMA = Path.of("..", "docs", "api", "input-schema.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Field table of the page (heading, sub-heading under it, and its place among the field tables there) to definition. */
    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("Etsy (`ETSY`)#1", "EtsyListing"),
            Map.entry("Etsy personalization questions#1", "EtsyPersonalizationQuestion"),
            Map.entry("Amazon (`AMAZON`)#1", "AmazonListing"),
            Map.entry("Amazon customization#1", "AmazonCustomization"),
            Map.entry("Amazon customization/Surfaces#1", "AmazonSurface"),
            Map.entry("Amazon customization/Text inputs#1", "AmazonTextInput"),
            Map.entry("Amazon customization/Dropdowns#1", "AmazonDropdown"),
            Map.entry("Amazon customization/Image inputs#1", "AmazonImageInput"),
            Map.entry("Variation checks#1", "VariationCheckRequest"),
            Map.entry("Variation checks#2", "VariationParent"),
            Map.entry("Variation checks#3", "VariationChild"),
            Map.entry("Personalization screening#1", "PersonalizationScreeningRequest"),
            Map.entry("Personalization screening#2", "PersonalizationValue"),
            Map.entry("Image checks#1", "ImageCheckMetadata"),
            Map.entry("Image checks#2", "ImageCheckImage"),
            Map.entry("Trademark checks#1", "TrademarkCheckRequest"));

    /** Definitions the page describes in prose or in the Prices table rather than in a field table. */
    private static final Set<String> PROSE_DEFINITIONS = Set.of("CheckRequest", "Marketplace", "Price", "EtsyPersonalization");

    /** The trademark table lists only the field next to the /checks envelope. */
    private static final Set<String> ENVELOPE = Set.of("marketplace", "listing");

    /** JSON example of the page (its name after ```json) to the definition it is a body of. */
    private static final Map<String, String> EXAMPLES = Map.of(
            "etsy-listing", "EtsyListing",
            "amazon-listing", "AmazonListing",
            "variation-amazon", "VariationCheckRequest",
            "variation-etsy", "VariationCheckRequest",
            "personalization-order", "PersonalizationScreeningRequest",
            "image-metadata", "ImageCheckMetadata");

    private record Table(String key, Map<String, String> typesByField) {
    }

    private static JsonNode schema;
    private static List<Table> tables;
    private static Map<String, String> examples;

    @BeforeAll
    static void read() throws IOException {
        schema = MAPPER.readTree(Files.readString(SCHEMA, StandardCharsets.UTF_8));
        List<String> lines = Files.readAllLines(PAGE, StandardCharsets.UTF_8);
        tables = tables(lines);
        examples = examples(lines);
    }

    @Test
    void everyFieldTableOfThePageIsADefinitionWithTheSameFields() {
        Set<String> mapped = new TreeSet<>();
        for (Table table : tables) {
            String definition = TABLES.get(table.key());
            assertThat(definition).as("field table \"" + table.key() + "\" of docs/input-schema.md has no definition in "
                    + "input-schema.json (map it in TABLES)").isNotNull();
            mapped.add(table.key());
            Set<String> properties = new TreeSet<>(CompatibilityChecker.fieldNames(definition(definition).path("properties")));
            if ("TrademarkCheckRequest".equals(definition)) {
                properties.removeAll(ENVELOPE);
            }
            assertThat(properties).as("fields of " + definition + " = the table \"" + table.key() + "\"")
                    .containsExactlyInAnyOrderElementsOf(table.typesByField().keySet());
        }
        assertThat(mapped).as("every mapped table is still on the page").containsExactlyInAnyOrderElementsOf(TABLES.keySet());
    }

    @Test
    void everyDefinitionIsDescribedByThePage() {
        Set<String> described = new TreeSet<>(TABLES.values());
        described.addAll(PROSE_DEFINITIONS);
        assertThat(new TreeSet<>(CompatibilityChecker.fieldNames(schema.path("$defs")))).containsExactlyInAnyOrderElementsOf(described);
        assertThat(schema.path("$ref").asText()).as("the root is the /checks request").isEqualTo("#/$defs/CheckRequest");
        assertThat(CompatibilityChecker.fieldNames(definition("Price").at("/anyOf/0/properties")))
                .as("the object form of Prices").containsExactlyInAnyOrder("amount", "divisor", "currency_code", "currency");
    }

    @Test
    void theTypeOfEveryFieldIsTheTypeThePageGives() {
        for (Table table : tables) {
            JsonNode properties = definition(TABLES.get(table.key())).path("properties");
            table.typesByField().forEach((field, type) -> assertThat(matches(type, properties.get(field)))
                    .as(TABLES.get(table.key()) + "." + field + " is \"" + type + "\" on the page").isTrue());
        }
    }

    @Test
    void theSchemaIsAValidDraft202012Schema() {
        Schema metaSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        assertThat(metaSchema.validate(schema)).isEmpty();
    }

    @Test
    void everyExampleOfThePageIsValid() throws IOException {
        assertThat(examples.keySet()).as("JSON examples of docs/input-schema.md").containsExactlyInAnyOrderElementsOf(EXAMPLES.keySet());
        for (Map.Entry<String, String> example : examples.entrySet()) {
            JsonNode body = MAPPER.readTree(example.getValue());
            assertThat(errors(EXAMPLES.get(example.getKey()), body)).as("example " + example.getKey()).isEmpty();
        }
        // the two listings inside the /checks envelope, through the root and its choice by marketplace
        ObjectNode etsy = MAPPER.createObjectNode().put("marketplace", "etsy");
        etsy.set("listing", MAPPER.readTree(examples.get("etsy-listing")));
        ObjectNode amazon = MAPPER.createObjectNode().put("marketplace", "AMAZON");
        amazon.set("listing", MAPPER.readTree(examples.get("amazon-listing")));
        assertThat(errors("CheckRequest", etsy)).isEmpty();
        assertThat(errors("CheckRequest", amazon)).isEmpty();
        ObjectNode trademark = amazon.deepCopy().put("product_type", "apparel");
        assertThat(errors("TrademarkCheckRequest", trademark)).isEmpty();
    }

    @Test
    void theSchemaRefusesWhatThePageRefuses() throws IOException {
        // the marketplace picks the listing format: Etsy tags are an array
        assertThat(errors("CheckRequest", MAPPER.readTree("{\"marketplace\": \"ETSY\", \"listing\": {\"tags\": \"mug\"}}"))).isNotEmpty();
        assertThat(errors("CheckRequest", MAPPER.readTree("{\"marketplace\": \"ETSY\"}"))).as("listing is required").isNotEmpty();
        // unknown members are ignored by the API, except in the image metadata, which is read strictly
        assertThat(errors("CheckRequest", MAPPER.readTree("{\"marketplace\": \"ETSY\", \"listing\": {\"shop_section\": \"x\"}}"))).isEmpty();
        assertThat(errors("ImageCheckMetadata", MAPPER.readTree(
                "{\"marketplace\": \"ETSY\", \"images\": [{\"role\": \"MAIN\"}], \"note\": \"x\"}"))).isNotEmpty();
        assertThat(errors("ImageCheckMetadata", MAPPER.readTree(
                "{\"marketplace\": \"ETSY\", \"images\": [{\"role\": \"main\"}]}"))).as("roles are exact names").isNotEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static JsonNode definition(String name) {
        JsonNode definition = schema.path("$defs").path(name);
        assertThat(definition.isObject()).as("definition " + name).isTrue();
        return definition;
    }

    /** Validates a body against one definition: a copy of the schema whose root points at it. */
    private static List<String> errors(String definition, JsonNode body) {
        ObjectNode copy = schema.deepCopy();
        copy.remove("$id");
        copy.put("$ref", "#/$defs/" + definition);
        Schema validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(copy.toString());
        return validator.validate(body).stream().map(Error::getMessage).toList();
    }

    /** Whether a property of the schema has the type a table of the page writes. */
    private static boolean matches(String pageType, JsonNode property) {
        if (property == null) {
            return false;
        }
        return switch (pageType) {
            case "string" -> types(property).equals(Set.of("string"));
            case "number" -> Set.of("number").equals(types(property)) || Set.of("integer").equals(types(property));
            case "boolean" -> types(property).equals(Set.of("boolean"));
            case "object" -> types(property).equals(Set.of("object"));
            case "array" -> types(property).equals(Set.of("array"));
            case "array of strings" -> types(property).equals(Set.of("array")) && types(resolve(property).get("items")).equals(Set.of("string"));
            case "array of objects" -> types(property).equals(Set.of("array")) && types(resolve(property).get("items")).equals(Set.of("object"));
            case "see Prices" -> "#/$defs/Price".equals(property.path("$ref").asText());
            case "string, or array of strings" -> property.path("anyOf").size() == 2
                    && types(property.at("/anyOf/0")).equals(Set.of("string"))
                    && types(property.at("/anyOf/1")).equals(Set.of("array"))
                    && types(property.at("/anyOf/1/items")).equals(Set.of("string"));
            default -> throw new AssertionError("A type on docs/input-schema.md this test does not know: \"" + pageType + "\"");
        };
    }

    private static JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        while (current != null && current.has("$ref")) {
            current = schema.at(current.get("$ref").asText().substring(1));
        }
        return current;
    }

    /** The non-null types of a schema after its reference was followed. */
    private static Set<String> types(JsonNode node) {
        JsonNode resolved = resolve(node);
        Set<String> types = new TreeSet<>();
        JsonNode type = resolved == null ? null : resolved.get("type");
        if (type != null && type.isArray()) {
            type.forEach(each -> types.add(each.asText()));
        } else if (type != null) {
            types.add(type.asText());
        }
        types.remove("null");
        return types;
    }

    private static final Pattern FIELD_ROW = Pattern.compile("^\\| `([a-z_]+)` \\| ([^|]+?) \\|.*$");

    /** The field tables of the page (header {@code | Field | Type | Notes |}), keyed by heading and place. */
    private static List<Table> tables(List<String> lines) {
        List<Table> found = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        String section = "";
        String subsection = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                subsection = null;
            } else if (line.startsWith("### ")) {
                subsection = line.substring(4).trim();
            } else if (line.startsWith("| Field | Type |")) {
                String group = subsection == null ? section : section + "/" + subsection;
                int ordinal = counts.merge(group, 1, Integer::sum);
                Map<String, String> fields = new LinkedHashMap<>();
                for (int row = i + 2; row < lines.size() && lines.get(row).startsWith("|"); row++) {
                    Matcher matcher = FIELD_ROW.matcher(lines.get(row));
                    assertThat(matcher.matches()).as("a field row of \"" + group + "\": " + lines.get(row)).isTrue();
                    fields.put(matcher.group(1), matcher.group(2).trim());
                }
                found.add(new Table(group + "#" + ordinal, fields));
            }
        }
        return found;
    }

    private static final Pattern EXAMPLE_START = Pattern.compile("^```json ([a-z-]+)$");

    /** The named JSON examples of the page: {@code ```json <name>} ... {@code ```}. */
    private static Map<String, String> examples(List<String> lines) {
        Map<String, String> found = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher start = EXAMPLE_START.matcher(lines.get(i));
            if (!start.matches()) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            int line = i + 1;
            for (; line < lines.size() && !lines.get(line).equals("```"); line++) {
                body.append(lines.get(line)).append('\n');
            }
            assertThat(names.add(start.group(1))).as("example named twice: " + start.group(1)).isTrue();
            found.put(start.group(1), body.toString());
            i = line;
        }
        return found;
    }
}
