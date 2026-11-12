package com.thanhdatpb.listingguard.client;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
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
import com.thanhdatpb.listingguard.client.model.PersonalizationValue;
import com.thanhdatpb.listingguard.client.model.PlansCapability;
import com.thanhdatpb.listingguard.client.model.Readiness;
import com.thanhdatpb.listingguard.client.model.RiskAdjustment;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import com.thanhdatpb.listingguard.client.model.SourceLink;
import com.thanhdatpb.listingguard.client.model.Summary;
import com.thanhdatpb.listingguard.client.model.TrademarkCatalogCapability;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckFinding;
import com.thanhdatpb.listingguard.client.model.TsdrStatus;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckResult;
import com.thanhdatpb.listingguard.client.model.Usage;
import com.thanhdatpb.listingguard.client.model.VariationCheckRequest;
import com.thanhdatpb.listingguard.client.model.VariationCheckResult;
import com.thanhdatpb.listingguard.client.model.VariationChild;
import com.thanhdatpb.listingguard.client.model.VariationParent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The SDK's records against the contract documents of ADR-022, as they are now (ADR-023). It is the client side of the contract:
 * {@code contract-tools} holds the documents compatible with the latest release (the server side), and this test holds the SDK
 * to the documents, so the two checks do not overlap.
 *
 * <ul>
 *   <li>Responses follow {@code docs/api/openapi.json}: the same member names, a compatible Java type, a boxed type for a nullable
 *       member, enum constants equal to the schema's values plus {@code UNRECOGNIZED}; every schema reachable from the seven
 *       operations' {@code 200} answers is mapped; every example of those answers is read without an unrecognized value; every
 *       documented problem becomes the exception of its code.</li>
 *   <li>Requests follow {@code docs/api/input-schema.json}, the machine-readable request formats of ADR-022 (the OpenAPI document
 *       types four request members that are JSON values as strings, see the README Roadmap): the same member names and
 *       compatible types, every definition reachable from the four request bodies is mapped, and each operation's OpenAPI request
 *       body names the same definition.</li>
 * </ul>
 *
 * <p>It is strict on purpose: a new response member or enum value is additive for an API client (ADR-022) but fails here until
 * the SDK maps it, so the official SDK follows the API in the same pull request. A breaking change is stopped first by
 * {@code contract-tools}.
 */
class ContractConformanceTest {

    private static final Path OPENAPI = Path.of("../docs/api/openapi.json");
    private static final Path INPUT_SCHEMA = Path.of("../docs/api/input-schema.json");

    /** Each SDK response record and the OpenAPI schema it follows. */
    static final Map<Class<?>, String> RESPONSES = Map.ofEntries(
            entry(CheckResult.class, "CheckResult"),
            entry(Finding.class, "FindingView"),
            entry(Fix.class, "FixView"),
            entry(Readiness.class, "ReadinessView"),
            entry(Summary.class, "Summary"),
            entry(TrademarkCheckResult.class, "TrademarkCheckResult"),
            entry(TrademarkCheckFinding.class, "TrademarkCheckFindingView"),
            entry(RiskAdjustment.class, "RiskAdjustmentView"),
            entry(SourceLink.class, "SourceLinkView"),
            entry(TsdrStatus.class, "TsdrStatusView"),
            entry(VariationCheckResult.class, "VariationCheckResult"),
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

    /** Each SDK request record and the input-schema definition it follows. */
    static final Map<Class<?>, String> REQUESTS = Map.ofEntries(
            entry(CheckRequest.class, "CheckRequest"),
            entry(TrademarkCheckRequest.class, "TrademarkCheckRequest"),
            entry(VariationCheckRequest.class, "VariationCheckRequest"),
            entry(VariationParent.class, "VariationParent"),
            entry(VariationChild.class, "VariationChild"),
            entry(PersonalizationScreeningRequest.class, "PersonalizationScreeningRequest"),
            entry(PersonalizationValue.class, "PersonalizationValue"));

    /** The request record each POST operation sends. */
    static final Map<Operation, Class<?>> REQUEST_OF = Map.of(
            Operation.CHECKS, CheckRequest.class,
            Operation.TRADEMARK_CHECKS, TrademarkCheckRequest.class,
            Operation.VARIATION_CHECKS, VariationCheckRequest.class,
            Operation.PERSONALIZATION_SCREENINGS, PersonalizationScreeningRequest.class);

    private static JsonNode doc;
    private static JsonNode schemas;
    private static JsonNode defs;

    @BeforeAll
    static void readDocuments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        doc = mapper.readTree(Files.readString(OPENAPI));
        schemas = doc.at("/components/schemas");
        defs = mapper.readTree(Files.readString(INPUT_SCHEMA)).path("$defs");
    }

    // ---- responses: openapi.json ------------------------------------------------------------------------------------

    @Test
    void everyResponseRecordHasExactlyTheMembersOfItsSchema() {
        for (Map.Entry<Class<?>, String> e : RESPONSES.entrySet()) {
            JsonNode schema = schemas.get(e.getValue());
            assertThat(schema).as("schema %s", e.getValue()).isNotNull();
            assertThat(new TreeSet<>(jsonNames(e.getKey()).keySet())).as("%s vs %s", e.getKey().getSimpleName(), e.getValue())
                    .isEqualTo(names(schema));
        }
    }

    @Test
    void everyResponseMemberHasACompatibleTypeAndEveryNullableOneIsBoxed() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Class<?>, String> e : RESPONSES.entrySet()) {
            JsonNode properties = schemas.get(e.getValue()).path("properties");
            jsonNames(e.getKey()).forEach((name, component) -> {
                JsonNode property = properties.get(name);
                if (property != null) {
                    checkResponse(e.getValue() + "." + name, property, component.getGenericType(), problems);
                }
            });
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everySchemaReachableFromTheSevenAnswersIsMapped() {
        Set<String> reachable = new LinkedHashSet<>();
        for (Operation operation : Operation.values()) {
            Deque<JsonNode> todo = new ArrayDeque<>();
            todo.add(operation(operation).at("/responses/200/content/application~1json/schema"));
            walk(todo, reachable, "#/components/schemas/", schemas);
        }
        assertThat(reachable).as("schemas the seven answers use").isNotEmpty();
        assertThat(new HashSet<>(RESPONSES.values())).containsAll(reachable);
    }

    @Test
    void everySuccessExampleOfTheSevenOperationsIsReadWithoutAnUnrecognizedValue() throws Exception {
        ObjectMapper sdk = Json.newMapper();
        int read = 0;
        for (Operation operation : Operation.values()) {
            JsonNode content = operation(operation).at("/responses/200/content/application~1json");
            Class<?> type = RESPONSES.entrySet().stream()
                    .filter(e -> content.at("/schema/$ref").asText().endsWith("/" + e.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            for (JsonNode example : examples(content)) {
                List<String> unrecognized = new ArrayList<>();
                findUnrecognized(operation.toString(), sdk.treeToValue(example, type), unrecognized);
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
                for (JsonNode example : examples(response.getValue().at("/content/application~1problem+json"))) {
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

    // ---- requests: input-schema.json --------------------------------------------------------------------------------

    @Test
    void everyRequestRecordHasExactlyTheMembersOfItsDefinition() {
        for (Map.Entry<Class<?>, String> e : REQUESTS.entrySet()) {
            JsonNode definition = defs.get(e.getValue());
            assertThat(definition).as("definition %s", e.getValue()).isNotNull();
            assertThat(new TreeSet<>(jsonNames(e.getKey()).keySet())).as("%s vs %s", e.getKey().getSimpleName(), e.getValue())
                    .isEqualTo(names(definition));
        }
    }

    @Test
    void everyRequestMemberHasACompatibleType() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Class<?>, String> e : REQUESTS.entrySet()) {
            JsonNode properties = defs.get(e.getValue()).path("properties");
            jsonNames(e.getKey()).forEach((name, component) ->
                    checkRequest(e.getValue() + "." + name, properties.get(name), component.getGenericType(), problems));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyDefinitionReachableFromTheFourRequestBodiesIsMapped() {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<JsonNode> todo = new ArrayDeque<>();
        for (Class<?> request : REQUEST_OF.values()) {
            todo.add(defs.get(REQUESTS.get(request)));
        }
        walk(todo, reachable, "#/$defs/", defs);
        reachable.removeIf(name -> !defs.get(name).path("type").asText().equals("object"));
        assertThat(new HashSet<>(REQUESTS.values())).containsAll(reachable);
    }

    @Test
    void eachPostOperationNamesTheSameRequestInBothDocuments() {
        REQUEST_OF.forEach((operation, record) -> assertThat(
                operation(operation).at("/requestBody/content/application~1json/schema/$ref").asText())
                .as(operation.toString()).isEqualTo("#/components/schemas/" + REQUESTS.get(record)));
    }

    @Test
    void theRequiredMembersOfARequestAreMembersOfTheRecord() {
        for (Map.Entry<Class<?>, String> e : REQUESTS.entrySet()) {
            Set<String> required = new TreeSet<>();
            defs.get(e.getValue()).path("required").forEach(r -> required.add(r.asText()));
            assertThat(jsonNames(e.getKey()).keySet()).as(e.getValue()).containsAll(required);
        }
    }

    @Test
    void theOperationsOfTheSdkAreTheDocumentedOnes() {
        for (Operation operation : Operation.values()) {
            assertThat(operation(operation).isMissingNode()).as(operation.toString()).isFalse();
        }
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
        return doc.path("paths").path(operation.path()).path(operation.method().toLowerCase(Locale.ROOT));
    }

    private static Set<String> names(JsonNode schema) {
        Set<String> out = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Follows $ref, properties, items, anyOf, oneOf and additionalProperties; not allOf/if/then (a listing format choice). */
    private static void walk(Deque<JsonNode> todo, Set<String> reachable, String prefix, JsonNode named) {
        while (!todo.isEmpty()) {
            JsonNode node = todo.poll();
            if (node == null || node.isMissingNode()) {
                continue;
            }
            if (node.has("$ref")) {
                String name = node.get("$ref").asText().replace(prefix, "");
                if (reachable.add(name)) {
                    todo.add(named.get(name));
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
        reachable.remove("JsonNode");
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

    private static void checkResponse(String where, JsonNode property, Type javaType, List<String> problems) {
        Set<String> types = types(property);
        Class<?> raw = rawClass(javaType);
        if (types.contains("null") && raw.isPrimitive()) {
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
            if (!name.equals(RESPONSES.get(raw))) {
                problems.add(where + ": refers to " + name + " but the SDK type " + raw.getSimpleName() + " follows "
                        + RESPONSES.get(raw));
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
            if (expect(where, raw, problems, List.class)) {
                checkResponse(where + "[]", property.get("items"), typeArgument(javaType, 0), problems);
            }
        } else if (types.contains("object") && property.path("additionalProperties").isObject()) {
            if (expect(where, raw, problems, Map.class)) {
                checkResponse(where + "{}", property.get("additionalProperties"), typeArgument(javaType, 1), problems);
            }
        } else {
            problems.add(where + ": a document type the test does not know: " + types);
        }
    }

    /** A request member the SDK writes: its Java type must produce a value the definition accepts. */
    private static void checkRequest(String where, JsonNode property, Type javaType, List<String> problems) {
        Class<?> raw = rawClass(javaType);
        if (property.has("$ref")) {
            String name = property.get("$ref").asText().replace("#/$defs/", "");
            JsonNode definition = defs.get(name);
            if (definition.path("type").asText().equals("object")) {
                if (!name.equals(REQUESTS.get(raw))) {
                    problems.add(where + ": refers to " + name + " but the SDK type " + raw.getSimpleName() + " follows "
                            + REQUESTS.get(raw));
                }
                return;
            }
            checkRequest(where, definition, javaType, problems);
            return;
        }
        Set<String> types = types(property);
        if (types.contains("object") && !property.has("properties") && !property.path("additionalProperties").isObject()) {
            expect(where, raw, problems, Object.class); // a listing: any object Jackson writes as JSON
        } else if (types.contains("object")) {
            if (expect(where, raw, problems, Map.class)) {
                checkRequest(where + "{}", property.get("additionalProperties"), typeArgument(javaType, 1), problems);
            }
        } else if (types.contains("array")) {
            if (expect(where, raw, problems, List.class)) {
                checkRequest(where + "[]", property.get("items"), typeArgument(javaType, 0), problems);
            }
        } else if (types.contains("string")) {
            expect(where, raw, problems, String.class);
        } else if (types.contains("integer")) {
            expect(where, raw, problems, Integer.class, Long.class);
        } else if (types.contains("number")) {
            expect(where, raw, problems, Double.class, BigDecimal.class);
        } else if (types.contains("boolean")) {
            expect(where, raw, problems, Boolean.class);
        } else {
            problems.add(where + ": a definition type the test does not know: " + types);
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
            if (unrecognized.getAnnotation(JsonEnumDefaultValue.class) == null) {
                problems.add(where + ": UNRECOGNIZED of " + raw.getSimpleName() + " is not the default value");
            }
        } catch (NoSuchFieldException e) {
            problems.add(where + ": " + raw.getSimpleName() + " has no UNRECOGNIZED");
        }
    }

    private static boolean expect(String where, Class<?> raw, List<String> problems, Class<?>... allowed) {
        if (!Arrays.asList(allowed).contains(raw)) {
            problems.add(where + ": expected one of " + Arrays.stream(allowed).map(Class::getSimpleName).toList() + ", is "
                    + raw.getSimpleName());
            return false;
        }
        return true;
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
