package com.thanhdatpb.listingguard.client;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.model.Capabilities;
import com.thanhdatpb.listingguard.client.model.CharacterFinding;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.CheckResult;
import com.thanhdatpb.listingguard.client.model.Finding;
import com.thanhdatpb.listingguard.client.model.Fix;
import com.thanhdatpb.listingguard.client.model.FixesCapability;
import com.thanhdatpb.listingguard.client.model.Limit;
import com.thanhdatpb.listingguard.client.model.PackCapability;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningRequest;
import com.thanhdatpb.listingguard.client.model.PersonalizationScreeningResult;
import com.thanhdatpb.listingguard.client.model.PersonalizationTrademark;
import com.thanhdatpb.listingguard.client.model.PersonalizationTrademarkFinding;
import com.thanhdatpb.listingguard.client.model.PlansCapability;
import com.thanhdatpb.listingguard.client.model.Readiness;
import com.thanhdatpb.listingguard.client.model.RiskAdjustment;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import com.thanhdatpb.listingguard.client.model.SourceLink;
import com.thanhdatpb.listingguard.client.model.Summary;
import com.thanhdatpb.listingguard.client.model.TrademarkCatalogCapability;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckFinding;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckResult;
import com.thanhdatpb.listingguard.client.model.Usage;
import com.thanhdatpb.listingguard.client.model.VariationCheckRequest;
import com.thanhdatpb.listingguard.client.model.VariationCheckResult;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The SDK's records against {@code docs/api/openapi.json} (ADR-023): the same member names, a compatible Java type, a boxed
 * type for a nullable member, enum constants equal to the schema's values plus {@code UNRECOGNIZED}; every schema reachable
 * from the seven operations is mapped; every example of those operations is read without an unrecognized value, and every
 * documented problem becomes the exception of its code.
 */
class OpenApiContractTest {

    private static final Path OPENAPI = Path.of("../docs/api/openapi.json");

    /** Each SDK record and the schema it follows. */
    static final Map<Class<?>, String> SCHEMAS = Map.ofEntries(
            entry(CheckRequest.class, "CheckRequest"),
            entry(CheckResult.class, "CheckResult"),
            entry(Finding.class, "FindingView"),
            entry(Fix.class, "FixView"),
            entry(Readiness.class, "ReadinessView"),
            entry(Summary.class, "Summary"),
            entry(TrademarkCheckRequest.class, "TrademarkCheckRequest"),
            entry(TrademarkCheckResult.class, "TrademarkCheckResult"),
            entry(TrademarkCheckFinding.class, "TrademarkCheckFindingView"),
            entry(RiskAdjustment.class, "RiskAdjustmentView"),
            entry(SourceLink.class, "SourceLinkView"),
            entry(VariationCheckRequest.class, "VariationCheckRequest"),
            entry(VariationCheckResult.class, "VariationCheckResult"),
            entry(PersonalizationScreeningRequest.class, "PersonalizationScreeningRequest"),
            entry(PersonalizationScreeningResult.class, "PersonalizationScreeningResult"),
            entry(CharacterFinding.class, "CharacterFindingView"),
            entry(PersonalizationTrademark.class, "TrademarkView"),
            entry(PersonalizationTrademarkFinding.class, "TrademarkFindingView"),
            entry(RulesetLimits.class, "RulesetLimitsResult"),
            entry(Limit.class, "LimitView"),
            entry(Capabilities.class, "Capabilities"),
            entry(PackCapability.class, "PackCapability"),
            entry(TrademarkCatalogCapability.class, "TrademarkCatalogCapability"),
            entry(PlansCapability.class, "PlansCapability"),
            entry(FixesCapability.class, "FixesCapability"),
            entry(Usage.class, "Usage"));

    /**
     * Request members that are JSON values (an object or an array) but that the document types as {@code string}: springdoc
     * writes a {@code JsonNode} member with a {@code type = "string"} hint that way. The SDK follows docs/input-schema.md for
     * them (typed records and lists). A known difference of the document, not of the API.
     */
    static final Set<String> DOCUMENT_TYPES_A_JSON_VALUE_AS_STRING = Set.of(
            "VariationCheckRequest.parent",
            "VariationCheckRequest.children",
            "VariationCheckRequest.variation_properties",
            "PersonalizationScreeningRequest.values");

    /** The schema of a free JSON value: the SDK takes any object for it. */
    private static final String FREE_JSON = "JsonNode";

    private static JsonNode doc;
    private static JsonNode schemas;

    @BeforeAll
    static void readDocument() throws Exception {
        doc = new ObjectMapper().readTree(Files.readString(OPENAPI));
        schemas = doc.at("/components/schemas");
    }

    // ---- members and types --------------------------------------------------------------------------------------------

    @Test
    void everyRecordHasExactlyTheMembersOfItsSchema() {
        for (Map.Entry<Class<?>, String> e : SCHEMAS.entrySet()) {
            JsonNode schema = schemas.get(e.getValue());
            assertThat(schema).as("schema %s", e.getValue()).isNotNull();
            Set<String> documented = new TreeSet<>();
            schema.path("properties").fieldNames().forEachRemaining(documented::add);
            assertThat(new TreeSet<>(jsonNames(e.getKey()).keySet())).as("%s vs %s", e.getKey().getSimpleName(), e.getValue())
                    .isEqualTo(documented);
        }
    }

    @Test
    void everyMemberHasACompatibleTypeAndEveryNullableOneIsBoxed() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Class<?>, String> e : SCHEMAS.entrySet()) {
            JsonNode properties = schemas.get(e.getValue()).path("properties");
            for (Map.Entry<String, RecordComponent> member : jsonNames(e.getKey()).entrySet()) {
                String where = e.getValue() + "." + member.getKey();
                JsonNode property = properties.get(member.getKey());
                if (property == null || DOCUMENT_TYPES_A_JSON_VALUE_AS_STRING.contains(where)) {
                    continue;
                }
                check(where, property, member.getValue().getGenericType(), problems);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theKnownDifferencesOfTheDocumentAreStillThere() {
        // When the document is fixed, this fails: remove the entry and let the type check cover the member.
        for (String where : DOCUMENT_TYPES_A_JSON_VALUE_AS_STRING) {
            String[] parts = where.split("\\.");
            JsonNode property = schemas.get(parts[0]).path("properties").get(parts[1]);
            assertThat(types(property)).as(where).contains("string");
        }
    }

    @Test
    void everySchemaReachableFromTheSevenOperationsIsMapped() {
        Set<String> mapped = new HashSet<>(SCHEMAS.values());
        Set<String> reachable = new LinkedHashSet<>();
        for (Operation operation : Operation.values()) {
            JsonNode op = operation(operation);
            Deque<JsonNode> todo = new ArrayDeque<>();
            todo.add(op.at("/requestBody/content/application~1json/schema"));
            todo.add(op.at("/responses/200/content/application~1json/schema"));
            while (!todo.isEmpty()) {
                JsonNode node = todo.poll();
                if (node == null || node.isMissingNode()) {
                    continue;
                }
                if (node.has("$ref")) {
                    String name = node.get("$ref").asText().replace("#/components/schemas/", "");
                    if (reachable.add(name)) {
                        todo.add(schemas.get(name));
                    }
                    continue;
                }
                node.path("properties").forEach(todo::add);
                todo.add(node.path("items"));
                node.path("anyOf").forEach(todo::add);
                node.path("oneOf").forEach(todo::add);
                if (node.path("additionalProperties").isObject()) {
                    todo.add(node.get("additionalProperties"));
                }
            }
        }
        reachable.remove(FREE_JSON);
        assertThat(reachable).as("schemas the seven operations use").isNotEmpty();
        assertThat(mapped).containsAll(reachable);
    }

    @Test
    void theOperationsOfTheSdkAreTheDocumentedOnes() {
        for (Operation operation : Operation.values()) {
            assertThat(operation(operation).isMissingNode()).as(operation.toString()).isFalse();
        }
    }

    // ---- examples -----------------------------------------------------------------------------------------------------

    @Test
    void everySuccessExampleOfTheSevenOperationsIsReadWithoutAnUnrecognizedValue() throws Exception {
        ObjectMapper sdk = Json.newMapper();
        int read = 0;
        for (Operation operation : Operation.values()) {
            JsonNode content = operation(operation).at("/responses/200/content/application~1json");
            Class<?> type = SCHEMAS.entrySet().stream()
                    .filter(e -> content.at("/schema/$ref").asText().endsWith("/" + e.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            for (JsonNode example : examples(content)) {
                Object value = sdk.treeToValue(example, type);
                List<String> unrecognized = new ArrayList<>();
                findUnrecognized(operation + "", value, unrecognized);
                assertThat(unrecognized).as("%s example", operation).isEmpty();
                read++;
            }
        }
        assertThat(read).as("examples read").isGreaterThanOrEqualTo(Operation.values().length);
    }

    @Test
    void everyDocumentedProblemOfTheSevenOperationsBecomesTheExceptionOfItsCode() throws Exception {
        ObjectMapper sdk = Json.newMapper();
        int read = 0;
        for (Operation operation : Operation.values()) {
            for (Map.Entry<String, JsonNode> response : operation(operation).path("responses").properties()) {
                JsonNode content = response.getValue().at("/content/application~1problem+json");
                for (JsonNode example : examples(content)) {
                    RawResponse raw = new RawResponse(Integer.parseInt(response.getKey()), sdk.writeValueAsString(example),
                            HttpHeaders.of(Map.of(), (a, b) -> true));
                    ListingGuardApiException e = Problems.exception(sdk, raw, Instant.now());
                    String code = example.get("code").asText();
                    assertThat(e.code()).as("%s %s", operation, code).isEqualTo(code);
                    assertThat(e.isProblem()).isTrue();
                    assertThat(e.getClass()).as(code).isEqualTo(expectedException(code));
                    read++;
                }
            }
        }
        assertThat(read).as("problem examples read").isGreaterThan(10);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static Class<?> expectedException(String code) {
        return switch (code) {
            case "quota-exceeded" -> QuotaExceededException.class;
            case "plan-not-allowed" -> PlanNotAllowedException.class;
            case "rate-limited" -> RateLimitedException.class;
            case "invalid-request", "unprocessable-request" -> ValidationException.class;
            case "unknown-product-type" -> UnknownProductTypeException.class;
            default -> code.endsWith("-not-configured") ? NotConfiguredException.class : ListingGuardApiException.class;
        };
    }

    private static JsonNode operation(Operation operation) {
        return doc.path("paths").path(operation.path()).path(operation.method().toLowerCase(java.util.Locale.ROOT));
    }

    private static List<JsonNode> examples(JsonNode content) {
        List<JsonNode> out = new ArrayList<>();
        if (content.has("example") && content.get("example").isObject()) {
            out.add(content.get("example"));
        }
        content.path("examples").forEach(e -> {
            if (e.path("value").isObject()) {
                out.add(e.get("value"));
            }
        });
        return out;
    }

    /** JSON member name to record component, in declaration order. */
    private static Map<String, RecordComponent> jsonNames(Class<?> record) {
        assertThat(record.isRecord()).as(record.getSimpleName()).isTrue();
        Map<String, RecordComponent> out = new LinkedHashMap<>();
        for (RecordComponent c : record.getRecordComponents()) {
            // @JsonProperty has no RECORD_COMPONENT target: on a component it lands on the field (and the accessor).
            JsonProperty renamed;
            try {
                renamed = record.getDeclaredField(c.getName()).getAnnotation(JsonProperty.class);
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
            out.put(renamed != null && !renamed.value().isEmpty() ? renamed.value() : c.getName(), c);
        }
        return out;
    }

    private static Set<String> types(JsonNode property) {
        Set<String> out = new HashSet<>();
        JsonNode type = property.get("type");
        if (type != null && type.isTextual()) {
            out.add(type.asText());
        } else if (type != null && type.isArray()) {
            type.forEach(t -> out.add(t.asText()));
        }
        property.path("anyOf").forEach(alt -> {
            if (alt.has("type")) {
                out.add(alt.get("type").asText());
            }
        });
        return out;
    }

    private static void check(String where, JsonNode property, Type javaType, List<String> problems) {
        Set<String> types = types(property);
        boolean nullable = types.contains("null");
        Class<?> raw = rawClass(javaType);
        if (nullable && raw.isPrimitive()) {
            problems.add(where + ": nullable in the document but primitive " + raw.getSimpleName() + " in the SDK");
        }

        JsonNode ref = property.has("$ref") ? property : null;
        for (JsonNode alt : property.path("anyOf")) {
            if (alt.has("$ref")) {
                ref = alt;
            }
        }
        if (ref != null) {
            String name = ref.get("$ref").asText().replace("#/components/schemas/", "");
            if (FREE_JSON.equals(name)) {
                if (raw != Object.class) {
                    problems.add(where + ": a free JSON value must be Object, is " + raw.getSimpleName());
                }
                return;
            }
            if (!name.equals(SCHEMAS.get(raw))) {
                problems.add(where + ": refers to " + name + " but the SDK type " + raw.getSimpleName() + " follows "
                        + SCHEMAS.get(raw));
            }
            return;
        }

        String format = property.path("format").asText("");
        if (types.contains("string")) {
            if (property.has("enum")) {
                checkEnum(where, property.get("enum"), raw, problems);
            } else if (format.equals("uuid")) {
                expect(where, raw, problems, UUID.class);
            } else if (format.equals("date-time")) {
                expect(where, raw, problems, Instant.class);
            } else if (format.equals("date")) {
                expect(where, raw, problems, LocalDate.class);
            } else {
                expect(where, raw, problems, String.class);
            }
        } else if (types.contains("integer")) {
            if (format.equals("int64")) {
                expect(where, raw, problems, long.class, Long.class);
            } else {
                expect(where, raw, problems, int.class, Integer.class);
            }
        } else if (types.contains("number")) {
            expect(where, raw, problems, double.class, Double.class, BigDecimal.class);
        } else if (types.contains("boolean")) {
            expect(where, raw, problems, boolean.class, Boolean.class);
        } else if (types.contains("array")) {
            if (!List.class.equals(raw)) {
                problems.add(where + ": an array must be a List, is " + raw.getSimpleName());
                return;
            }
            check(where + "[]", property.get("items"), typeArgument(javaType, 0), problems);
        } else if (types.contains("object") && property.path("additionalProperties").isObject()) {
            if (!Map.class.equals(raw)) {
                problems.add(where + ": a map must be a Map, is " + raw.getSimpleName());
                return;
            }
            check(where + "{}", property.get("additionalProperties"), typeArgument(javaType, 1), problems);
        } else {
            problems.add(where + ": a document type the test does not know: " + types);
        }
    }

    private static void checkEnum(String where, JsonNode values, Class<?> raw, List<String> problems) {
        if (!raw.isEnum()) {
            problems.add(where + ": an enum in the document but " + raw.getSimpleName() + " in the SDK");
            return;
        }
        Set<String> documented = new TreeSet<>();
        values.forEach(v -> documented.add(v.asText()));
        documented.add("UNRECOGNIZED");
        Set<String> constants = new TreeSet<>();
        for (Object constant : raw.getEnumConstants()) {
            constants.add(((Enum<?>) constant).name());
        }
        if (!constants.equals(documented)) {
            problems.add(where + ": " + raw.getSimpleName() + " has " + constants + ", the document " + documented);
        }
        try {
            Field unrecognized = raw.getField("UNRECOGNIZED");
            if (unrecognized.getAnnotation(com.fasterxml.jackson.annotation.JsonEnumDefaultValue.class) == null) {
                problems.add(where + ": UNRECOGNIZED of " + raw.getSimpleName() + " is not the default value");
            }
        } catch (NoSuchFieldException e) {
            problems.add(where + ": " + raw.getSimpleName() + " has no UNRECOGNIZED");
        }
    }

    private static void expect(String where, Class<?> raw, List<String> problems, Class<?>... allowed) {
        if (!Arrays.asList(allowed).contains(raw)) {
            problems.add(where + ": expected one of " + Arrays.stream(allowed).map(Class::getSimpleName).toList() + ", is "
                    + raw.getSimpleName());
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        throw new AssertionError("unexpected type " + type);
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p) {
            return p.getActualTypeArguments()[index];
        }
        throw new AssertionError("not parameterized: " + type);
    }

    /** Collects every enum constant named UNRECOGNIZED reachable from a record. */
    private static void findUnrecognized(String where, Object value, List<String> out) throws Exception {
        if (value == null) {
            return;
        }
        if (value instanceof Enum<?> e) {
            if (e.name().equals("UNRECOGNIZED")) {
                out.add(where);
            }
            return;
        }
        if (value instanceof Collection<?> c) {
            int i = 0;
            for (Object item : c) {
                findUnrecognized(where + "[" + i++ + "]", item, out);
            }
            return;
        }
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                findUnrecognized(where + "{" + e.getKey() + "}", e.getValue(), out);
            }
            return;
        }
        if (value.getClass().isRecord()) {
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                findUnrecognized(where + "." + c.getName(), c.getAccessor().invoke(value), out);
            }
        }
    }
}
