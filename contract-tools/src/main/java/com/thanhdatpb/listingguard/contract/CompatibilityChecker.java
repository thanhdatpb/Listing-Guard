package com.thanhdatpb.listingguard.contract;

import static com.thanhdatpb.listingguard.contract.Direction.REQUEST;
import static com.thanhdatpb.listingguard.contract.Direction.RESPONSE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the contract of a release (the baseline) with the current one and classifies every difference with a {@link Rule}
 * (ADR-022, section 4). It reads two JSON documents and nothing else: no network, no file, no state between calls.
 *
 * <p>Operations are matched by method and path with path parameter names ignored. Schemas are compared structurally through
 * {@code $ref} in the direction of the data ({@link Direction}); a change inside a named schema is reported once, at
 * {@code schema <Name>.<path>}. Descriptions and examples are not compared, except the examples of
 * {@code application/problem+json} responses, from which the problem codes are read ({@link ProblemCatalog}).
 */
public final class CompatibilityChecker {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}/]*)}");
    private static final List<String> UPPER_BOUNDS = List.of("maxLength", "maximum", "exclusiveMaximum", "maxItems", "maxProperties");
    private static final List<String> LOWER_BOUNDS = List.of("minLength", "minimum", "exclusiveMinimum", "minItems", "minProperties");
    private static final Set<String> COUNT_LOWER_BOUNDS = Set.of("minLength", "minItems", "minProperties");

    private final Resolver baseline;
    private final Resolver current;
    private final Map<String, Change> changes = new LinkedHashMap<>();
    private final Set<String> comparedComponents = new HashSet<>();

    private CompatibilityChecker(JsonNode baseline, JsonNode current) {
        this.baseline = new Resolver(Objects.requireNonNull(baseline, "baseline"));
        this.current = new Resolver(Objects.requireNonNull(current, "current"));
    }

    /** Compares two OpenAPI documents (3.0 or 3.1). */
    public static List<Change> compareOpenApi(JsonNode baseline, JsonNode current) {
        CompatibilityChecker checker = new CompatibilityChecker(baseline, current);
        checker.compareOperations();
        checker.compareProblems();
        return checker.sorted();
    }

    /** Compares two JSON Schemas made of {@code $defs} (the input schema): every definition is a request. */
    public static List<Change> compareInputSchemas(JsonNode baseline, JsonNode current) {
        CompatibilityChecker checker = new CompatibilityChecker(baseline, current);
        JsonNode oldDefinitions = baseline.path("$defs");
        JsonNode newDefinitions = current.path("$defs");
        for (String name : fieldNames(oldDefinitions)) {
            if (!newDefinitions.has(name)) {
                checker.record(Rule.INPUT_DEFINITION_REMOVED, "schema " + name, "");
            } else {
                checker.compareSchema(REQUEST, "schema " + name, definitionReference(name), definitionReference(name));
            }
        }
        for (String name : fieldNames(newDefinitions)) {
            if (!oldDefinitions.has(name)) {
                checker.record(Rule.INPUT_DEFINITION_ADDED, "schema " + name, "");
            }
        }
        return checker.sorted();
    }

    // ---- operations -----------------------------------------------------------------------------------------------

    private record Operation(String method, String path, JsonNode node, JsonNode pathItem) {

        String label() {
            return method + " " + path;
        }
    }

    private void compareOperations() {
        Map<String, Operation> oldOperations = operations(baseline.root());
        Map<String, Operation> newOperations = operations(current.root());
        oldOperations.forEach((key, operation) -> {
            Operation replacement = newOperations.get(key);
            if (replacement == null) {
                record(Rule.OPERATION_REMOVED, operation.label(), "");
            } else {
                compareOperation(operation, replacement);
            }
        });
        newOperations.forEach((key, operation) -> {
            if (!oldOperations.containsKey(key)) {
                record(Rule.OPERATION_ADDED, operation.label(), "");
            }
        });
    }

    private static Map<String, Operation> operations(JsonNode document) {
        Map<String, Operation> operations = new LinkedHashMap<>();
        document.path("paths").fields().forEachRemaining(entry -> {
            for (String method : METHODS) {
                JsonNode operation = entry.getValue().get(method);
                if (operation != null && operation.isObject()) {
                    String upper = method.toUpperCase(Locale.ROOT);
                    operations.put(upper + " " + normalizedPath(entry.getKey()),
                            new Operation(upper, entry.getKey(), operation, entry.getValue()));
                }
            }
        });
        return operations;
    }

    /** {@code /api/v1/checks/{id}} and {@code /api/v1/checks/{checkId}} are one URL. */
    static String normalizedPath(String path) {
        return PATH_PARAMETER.matcher(path).replaceAll("{}");
    }

    private void compareOperation(Operation old, Operation now) {
        String at = old.label();
        String oldId = text(old.node(), "operationId");
        String newId = text(now.node(), "operationId");
        if (oldId != null && !oldId.equals(newId)) {
            record(Rule.OPERATION_ID_CHANGED, at, oldId + " -> " + describe(newId));
        }
        if (!deprecated(old.node()) && deprecated(now.node())) {
            record(Rule.OPERATION_DEPRECATED, at, "");
        }
        compareSecurity(at, old, now);
        compareParameters(at, old, now);
        compareRequestBody(at, old, now);
        compareResponses(at, old, now);
    }

    private void compareSecurity(String at, Operation old, Operation now) {
        Set<Set<String>> before = security(baseline, old);
        Set<Set<String>> after = security(current, now);
        if (anonymous(before) && !anonymous(after)) {
            record(Rule.SECURITY_ADDED, at, "now " + after);
        } else if (!anonymous(before) && anonymous(after)) {
            record(Rule.SECURITY_REMOVED, at, "was " + before);
        } else if (!anonymous(before) && !after.containsAll(before)) {
            record(Rule.SECURITY_CHANGED, at, before + " -> " + after);
        }
    }

    /** The alternatives of the effective security requirement: each a set of scheme names, all of which must be satisfied. */
    private static Set<Set<String>> security(Resolver document, Operation operation) {
        JsonNode requirements = operation.node().has("security") ? operation.node().get("security") : document.root().path("security");
        Set<Set<String>> alternatives = new HashSet<>();
        for (JsonNode requirement : requirements) {
            alternatives.add(new TreeSet<>(fieldNames(requirement)));
        }
        return alternatives;
    }

    private static boolean anonymous(Set<Set<String>> alternatives) {
        return alternatives.isEmpty() || alternatives.contains(Set.of());
    }

    // ---- parameters -----------------------------------------------------------------------------------------------

    private record Parameter(String key, String label, JsonNode node) {
    }

    private void compareParameters(String at, Operation old, Operation now) {
        Map<String, Parameter> before = parameters(baseline, old);
        Map<String, Parameter> after = parameters(current, now);
        before.forEach((key, parameter) -> {
            String location = at + " parameter " + parameter.label();
            Parameter replacement = after.get(key);
            if (replacement == null) {
                record(Rule.PARAMETER_REMOVED, location, "");
                return;
            }
            boolean wasRequired = requiredParameter(parameter.node());
            boolean isRequired = requiredParameter(replacement.node());
            if (!wasRequired && isRequired) {
                record(Rule.PARAMETER_BECAME_REQUIRED, location, "");
            } else if (wasRequired && !isRequired) {
                record(Rule.PARAMETER_BECAME_OPTIONAL, location, "");
            }
            if (!deprecated(parameter.node()) && deprecated(replacement.node())) {
                record(Rule.PARAMETER_DEPRECATED, location, "");
            }
            if (parameter.node().has("schema") && replacement.node().has("schema")) {
                compareSchema(REQUEST, location + " $", parameter.node().get("schema"), replacement.node().get("schema"));
            }
        });
        after.forEach((key, parameter) -> {
            if (!before.containsKey(key)) {
                Rule rule = requiredParameter(parameter.node()) ? Rule.REQUIRED_PARAMETER_ADDED : Rule.OPTIONAL_PARAMETER_ADDED;
                record(rule, at + " parameter " + parameter.label(), "");
            }
        });
    }

    /** Path-level and operation-level parameters; path parameters are keyed by their position in the template. */
    private static Map<String, Parameter> parameters(Resolver document, Operation operation) {
        List<String> pathNames = new ArrayList<>();
        Matcher matcher = PATH_PARAMETER.matcher(operation.path());
        while (matcher.find()) {
            pathNames.add(matcher.group(1));
        }
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        for (JsonNode source : List.of(operation.pathItem().path("parameters"), operation.node().path("parameters"))) {
            for (JsonNode raw : source) {
                JsonNode parameter = document.resolve(raw).node();
                String in = text(parameter, "in");
                String name = text(parameter, "name");
                if (in == null || name == null) {
                    continue;
                }
                String key = switch (in) {
                    case "path" -> "path#" + (pathNames.contains(name) ? String.valueOf(pathNames.indexOf(name)) : name);
                    case "header" -> "header:" + name.toLowerCase(Locale.ROOT);
                    default -> in + ":" + name;
                };
                parameters.put(key, new Parameter(key, in + ":" + name, parameter));
            }
        }
        return parameters;
    }

    private static boolean requiredParameter(JsonNode parameter) {
        return "path".equals(text(parameter, "in")) || parameter.path("required").asBoolean(false);
    }

    // ---- request body ---------------------------------------------------------------------------------------------

    private void compareRequestBody(String at, Operation old, Operation now) {
        JsonNode before = old.node().has("requestBody") ? baseline.resolve(old.node().get("requestBody")).node() : null;
        JsonNode after = now.node().has("requestBody") ? current.resolve(now.node().get("requestBody")).node() : null;
        String location = at + " request";
        if (before == null && after == null) {
            return;
        }
        if (before == null) {
            record(required(after) ? Rule.REQUIRED_REQUEST_BODY_ADDED : Rule.OPTIONAL_REQUEST_BODY_ADDED, location, "");
            return;
        }
        if (after == null) {
            record(Rule.REQUEST_BODY_REMOVED, location, "");
            return;
        }
        if (!required(before) && required(after)) {
            record(Rule.REQUEST_BODY_BECAME_REQUIRED, location, "");
        } else if (required(before) && !required(after)) {
            record(Rule.REQUEST_BODY_BECAME_OPTIONAL, location, "");
        }
        compareContent(REQUEST, location, before.path("content"), after.path("content"),
                Rule.REQUEST_MEDIA_TYPE_REMOVED, Rule.REQUEST_MEDIA_TYPE_ADDED);
    }

    private static boolean required(JsonNode node) {
        return node.path("required").asBoolean(false);
    }

    private void compareContent(Direction direction, String location, JsonNode before, JsonNode after, Rule removed, Rule added) {
        Map<String, JsonNode> oldMedia = byLowerCaseName(before);
        Map<String, JsonNode> newMedia = byLowerCaseName(after);
        oldMedia.forEach((media, content) -> {
            String at = location + " " + media;
            JsonNode replacement = newMedia.get(media);
            if (replacement == null) {
                record(removed, at, "");
            } else if (content.has("schema") && replacement.has("schema")) {
                compareSchema(direction, at + " $", content.get("schema"), replacement.get("schema"));
            }
        });
        newMedia.keySet().stream().filter(media -> !oldMedia.containsKey(media))
                .forEach(media -> record(added, location + " " + media, ""));
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    private void compareResponses(String at, Operation old, Operation now) {
        JsonNode before = old.node().path("responses");
        JsonNode after = now.node().path("responses");
        for (String code : fieldNames(before)) {
            String location = at + " response " + code;
            if (!after.has(code)) {
                record(success(code) ? Rule.SUCCESS_RESPONSE_REMOVED : Rule.ERROR_RESPONSE_REMOVED, location, "");
                continue;
            }
            JsonNode oldResponse = baseline.resolve(before.get(code)).node();
            JsonNode newResponse = current.resolve(after.get(code)).node();
            compareHeaders(location, oldResponse.path("headers"), newResponse.path("headers"));
            compareContent(RESPONSE, location, oldResponse.path("content"), newResponse.path("content"),
                    Rule.RESPONSE_MEDIA_TYPE_REMOVED, Rule.RESPONSE_MEDIA_TYPE_ADDED);
        }
        for (String code : fieldNames(after)) {
            if (!before.has(code)) {
                record(success(code) ? Rule.SUCCESS_RESPONSE_ADDED : Rule.ERROR_RESPONSE_ADDED, at + " response " + code, "");
            }
        }
    }

    /** {@code 2xx} (and {@code 2XX}) is a success; {@code 3xx}, {@code 4xx}, {@code 5xx} and {@code default} are not. */
    private static boolean success(String code) {
        return code.startsWith("2");
    }

    private void compareHeaders(String location, JsonNode before, JsonNode after) {
        Map<String, String> oldNames = lowerCaseNames(before);
        Map<String, String> newNames = lowerCaseNames(after);
        oldNames.forEach((lower, name) -> {
            if (!newNames.containsKey(lower)) {
                record(Rule.RESPONSE_HEADER_REMOVED, location + " header " + name, "");
            }
        });
        newNames.forEach((lower, name) -> {
            if (oldNames.containsKey(lower)) {
                return;
            }
            JsonNode header = after.get(name);
            if (header.has("$ref")) {
                // a shared header added to many responses is one change for the release notes; its removal from one response
                // is still reported at that response
                record(Rule.RESPONSE_HEADER_ADDED, "header " + name + " (shared)", "first added at " + location);
            } else {
                record(Rule.RESPONSE_HEADER_ADDED, location + " header " + name, "");
            }
        });
    }

    // ---- schemas --------------------------------------------------------------------------------------------------

    /**
     * A schema after {@code $ref}s were followed, with its nullability and its non-null types made explicit.
     *
     * @param alternatives the non-null alternatives of an {@code anyOf}/{@code oneOf} with more than one; empty otherwise
     */
    private record SchemaView(JsonNode raw, JsonNode node, String name, boolean nullable, Set<String> types,
                              List<SchemaView> alternatives) {

        boolean isObject() {
            return types.contains("object");
        }

        boolean isArray() {
            return types.contains("array");
        }

        boolean isNullOnly() {
            return nullable && types.isEmpty() && alternatives.isEmpty() && node.has("type");
        }

        String signature() {
            return name != null ? name : types.isEmpty() ? "any" : String.join("|", types);
        }
    }

    private SchemaView view(Resolver document, JsonNode raw) {
        Resolver.Resolved resolved = document.resolve(raw);
        JsonNode node = resolved.node() != null && resolved.node().isObject() ? resolved.node() : JsonNodeFactory.instance.objectNode();
        String name = resolved.name();
        boolean nullable = node.path("nullable").asBoolean(false);
        JsonNode union = node.has("anyOf") ? node.get("anyOf") : node.get("oneOf");
        if (union != null && union.isArray()) {
            List<SchemaView> alternatives = new ArrayList<>();
            for (JsonNode alternative : union) {
                SchemaView each = view(document, alternative);
                if (each.isNullOnly()) {
                    nullable = true;
                } else {
                    alternatives.add(each);
                }
            }
            if (alternatives.size() == 1) {
                SchemaView only = alternatives.getFirst();
                return new SchemaView(raw, only.node(), only.name() != null ? only.name() : name, nullable || only.nullable(),
                        only.types(), only.alternatives());
            }
            return new SchemaView(raw, node, name, nullable, Set.of(), alternatives);
        }
        if (node.has("allOf")) {
            node = merge(document, node);
        }
        Set<String> types = new TreeSet<>();
        JsonNode type = node.get("type");
        if (type != null && type.isArray()) {
            type.forEach(each -> types.add(each.asText()));
        } else if (type != null) {
            types.add(type.asText());
        }
        if (types.remove("null")) {
            nullable = true;
        }
        if (types.isEmpty() && !nullable) {
            if (node.has("properties") || node.path("additionalProperties").isObject()) {
                types.add("object");
            } else if (node.has("items")) {
                types.add("array");
            }
        }
        return new SchemaView(raw, node, name, nullable, types, List.of());
    }

    /** The members, required names and type of every part of an {@code allOf}, as one schema. */
    private static ObjectNode merge(Resolver document, JsonNode node) {
        ObjectNode merged = node.deepCopy();
        merged.remove("allOf");
        for (JsonNode raw : node.get("allOf")) {
            JsonNode part = document.resolve(raw).node();
            if (part == null || !part.isObject()) {
                continue;
            }
            if (part.has("allOf")) {
                part = merge(document, part);
            }
            JsonNode properties = part.path("properties");
            if (properties.isObject() && !properties.isEmpty()) {
                ObjectNode target = merged.has("properties") ? (ObjectNode) merged.get("properties") : merged.putObject("properties");
                properties.fields().forEachRemaining(entry -> {
                    if (!target.has(entry.getKey())) {
                        target.set(entry.getKey(), entry.getValue());
                    }
                });
            }
            if (part.path("required").isArray() && !part.path("required").isEmpty()) {
                ArrayNode required = merged.has("required") ? (ArrayNode) merged.get("required") : merged.putArray("required");
                Set<String> present = new HashSet<>();
                required.forEach(each -> present.add(each.asText()));
                part.get("required").forEach(each -> {
                    if (present.add(each.asText())) {
                        required.add(each.asText());
                    }
                });
            }
            if (!merged.has("type") && part.has("type")) {
                merged.set("type", part.get("type"));
            }
        }
        return merged;
    }

    private void compareSchema(Direction direction, String location, JsonNode oldRaw, JsonNode newRaw) {
        SchemaView old = view(baseline, oldRaw);
        SchemaView now = view(current, newRaw);
        // nullability belongs to the place that uses a schema (anyOf [ref, null]), so it is reported there
        compareNullability(direction, location, old, now);
        String at = location;
        if (old.name() != null && now.name() != null && !old.name().equals(now.name())) {
            record(Rule.SCHEMA_RENAMED, location, old.name() + " -> " + now.name());
        }
        if (old.name() != null) {
            // a renamed schema is compared with its new one; the location names both so it cannot be read as the old schema
            at = now.name() == null || now.name().equals(old.name())
                    ? "schema " + old.name()
                    : "schema " + now.name() + " (was " + old.name() + ")";
            if (!comparedComponents.add(direction + " " + old.name() + " " + now.name())) {
                return;
            }
        }
        if (!old.alternatives().isEmpty() || !now.alternatives().isEmpty()) {
            compareUnion(direction, at, old, now);
            return;
        }
        compareTypes(direction, at, old, now);
        compareFormat(direction, at, old, now);
        compareEnum(direction, at, old, now);
        if (direction == REQUEST) {
            compareConstraints(at, old.node(), now.node());
        }
        if (old.isObject() && now.isObject()) {
            compareProperties(direction, at, old.node(), now.node());
            compareAdditionalProperties(direction, at, old.node(), now.node());
        }
        if (old.isArray() && now.isArray() && old.node().has("items") && now.node().has("items")) {
            compareSchema(direction, at + "[]", old.node().get("items"), now.node().get("items"));
        }
    }

    private void compareNullability(Direction direction, String location, SchemaView old, SchemaView now) {
        if (old.nullable() == now.nullable()) {
            return;
        }
        if (direction == RESPONSE) {
            record(now.nullable() ? Rule.RESPONSE_BECAME_NULLABLE : Rule.RESPONSE_NO_LONGER_NULLABLE, location, "");
        } else {
            record(now.nullable() ? Rule.REQUEST_NULL_ACCEPTED : Rule.REQUEST_NULL_REJECTED, location, "");
        }
    }

    private void compareUnion(Direction direction, String at, SchemaView old, SchemaView now) {
        Map<String, SchemaView> before = bySignature(old.alternatives().isEmpty() ? List.of(old) : old.alternatives());
        Map<String, SchemaView> after = bySignature(now.alternatives().isEmpty() ? List.of(now) : now.alternatives());
        boolean broken = direction == RESPONSE
                ? !before.keySet().containsAll(after.keySet())
                : !after.keySet().containsAll(before.keySet());
        if (broken) {
            record(Rule.SCHEMA_UNION_CHANGED, at, before.keySet() + " -> " + after.keySet());
        }
        before.forEach((signature, alternative) -> {
            SchemaView replacement = after.get(signature);
            if (replacement != null && (alternative != old || replacement != now)) {
                compareSchema(direction, at + " (" + signature + ")", alternative.raw(), replacement.raw());
            }
        });
    }

    private static Map<String, SchemaView> bySignature(List<SchemaView> alternatives) {
        Map<String, SchemaView> bySignature = new LinkedHashMap<>();
        alternatives.forEach(alternative -> bySignature.putIfAbsent(alternative.signature(), alternative));
        return bySignature;
    }

    private void compareTypes(Direction direction, String at, SchemaView old, SchemaView now) {
        Set<String> before = old.types();
        Set<String> after = now.types();
        if (before.equals(after)) {
            return;
        }
        String detail = describeTypes(before) + " -> " + describeTypes(after);
        if (direction == RESPONSE) {
            if (before.isEmpty()) {
                record(Rule.RESPONSE_TYPE_NARROWED, at, detail);
            } else if (after.isEmpty() || !covers(before, after)) {
                record(Rule.RESPONSE_TYPE_CHANGED, at, detail);
            } else {
                record(Rule.RESPONSE_TYPE_NARROWED, at, detail);
            }
        } else {
            if (after.isEmpty()) {
                record(Rule.REQUEST_TYPE_WIDENED, at, detail);
            } else if (before.isEmpty() || !covers(after, before)) {
                record(Rule.REQUEST_TYPE_CHANGED, at, detail);
            } else {
                record(Rule.REQUEST_TYPE_WIDENED, at, detail);
            }
        }
    }

    /** Whether every type in {@code values} is one of {@code accepted}; an {@code integer} is a {@code number}. */
    private static boolean covers(Set<String> accepted, Set<String> values) {
        return values.stream().allMatch(type -> accepted.contains(type) || ("integer".equals(type) && accepted.contains("number")));
    }

    private void compareFormat(Direction direction, String at, SchemaView old, SchemaView now) {
        String before = text(old.node(), "format");
        String after = text(now.node(), "format");
        if (Objects.equals(before, after)) {
            return;
        }
        if (direction == RESPONSE && before != null) {
            record(Rule.RESPONSE_FORMAT_CHANGED, at, describe(before) + " -> " + describe(after));
        } else if (direction == REQUEST && after != null) {
            record(Rule.REQUEST_FORMAT_CHANGED, at, describe(before) + " -> " + describe(after));
        }
    }

    private void compareEnum(Direction direction, String at, SchemaView old, SchemaView now) {
        Set<String> before = enumValues(old.node());
        Set<String> after = enumValues(now.node());
        if (before == null && after == null) {
            return;
        }
        if (before == null) {
            record(direction == RESPONSE ? Rule.RESPONSE_ENUM_ADDED : Rule.REQUEST_ENUM_ADDED, at, "now " + after);
            return;
        }
        if (after == null) {
            record(direction == RESPONSE ? Rule.RESPONSE_ENUM_REMOVED : Rule.REQUEST_ENUM_REMOVED, at, "was " + before);
            return;
        }
        for (String value : after) {
            if (!before.contains(value)) {
                record(direction == RESPONSE ? Rule.RESPONSE_ENUM_VALUE_ADDED : Rule.REQUEST_ENUM_VALUE_ADDED, at + " enum " + value, "");
            }
        }
        for (String value : before) {
            if (!after.contains(value)) {
                record(direction == RESPONSE ? Rule.RESPONSE_ENUM_VALUE_REMOVED : Rule.REQUEST_ENUM_VALUE_REMOVED, at + " enum " + value, "");
            }
        }
    }

    /** The values of {@code enum} as text, without {@code null} (which is nullability); null when there is no enum. */
    private static Set<String> enumValues(JsonNode node) {
        JsonNode values = node.get("enum");
        if (values == null || !values.isArray()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isNull()) {
                result.add(value.isTextual() ? value.asText() : value.toString());
            }
        });
        return result;
    }

    private void compareConstraints(String at, JsonNode old, JsonNode now) {
        UPPER_BOUNDS.forEach(keyword -> compareBound(at, keyword, old.get(keyword), now.get(keyword), true));
        LOWER_BOUNDS.forEach(keyword -> compareBound(at, keyword, old.get(keyword), now.get(keyword), false));
        String before = text(old, "pattern");
        String after = text(now, "pattern");
        if (!Objects.equals(before, after)) {
            record(after == null ? Rule.REQUEST_CONSTRAINT_RELAXED : Rule.REQUEST_CONSTRAINT_TIGHTENED, at + " pattern",
                    describe(before) + " -> " + describe(after));
        }
    }

    private void compareBound(String at, String keyword, JsonNode old, JsonNode now, boolean upper) {
        BigDecimal before = old != null && old.isNumber() ? old.decimalValue() : null;
        BigDecimal after = now != null && now.isNumber() ? now.decimalValue() : null;
        if (before == null && after == null || before != null && after != null && before.compareTo(after) == 0) {
            return;
        }
        boolean tightened;
        if (before == null) {
            if (COUNT_LOWER_BOUNDS.contains(keyword) && after.signum() == 0) {
                return;
            }
            tightened = true;
        } else if (after == null) {
            tightened = false;
        } else {
            tightened = upper ? after.compareTo(before) < 0 : after.compareTo(before) > 0;
        }
        record(tightened ? Rule.REQUEST_CONSTRAINT_TIGHTENED : Rule.REQUEST_CONSTRAINT_RELAXED, at + " " + keyword,
                describe(before) + " -> " + describe(after));
    }

    private void compareProperties(Direction direction, String at, JsonNode old, JsonNode now) {
        JsonNode before = old.path("properties");
        JsonNode after = now.path("properties");
        Set<String> wasRequired = names(old.path("required"));
        Set<String> isRequired = names(now.path("required"));
        for (String name : fieldNames(before)) {
            String location = at + "." + name;
            if (!after.has(name)) {
                record(direction == RESPONSE ? Rule.RESPONSE_PROPERTY_REMOVED : Rule.REQUEST_PROPERTY_REMOVED, location, "");
                continue;
            }
            boolean required = wasRequired.contains(name);
            boolean nowRequired = isRequired.contains(name);
            if (direction == RESPONSE && required && !nowRequired) {
                record(Rule.RESPONSE_PROPERTY_BECAME_OPTIONAL, location, "");
            } else if (direction == RESPONSE && !required && nowRequired) {
                record(Rule.RESPONSE_PROPERTY_BECAME_REQUIRED, location, "");
            } else if (direction == REQUEST && !required && nowRequired) {
                record(Rule.REQUEST_PROPERTY_BECAME_REQUIRED, location, "");
            } else if (direction == REQUEST && required && !nowRequired) {
                record(Rule.REQUEST_PROPERTY_BECAME_OPTIONAL, location, "");
            }
            if (!deprecated(before.get(name)) && deprecated(after.get(name))) {
                record(Rule.PROPERTY_DEPRECATED, location, "");
            }
            compareSchema(direction, location, before.get(name), after.get(name));
        }
        for (String name : fieldNames(after)) {
            if (before.has(name)) {
                continue;
            }
            Rule rule = direction == RESPONSE ? Rule.RESPONSE_PROPERTY_ADDED
                    : isRequired.contains(name) ? Rule.REQUEST_REQUIRED_PROPERTY_ADDED : Rule.REQUEST_OPTIONAL_PROPERTY_ADDED;
            record(rule, at + "." + name, "");
        }
    }

    private void compareAdditionalProperties(Direction direction, String at, JsonNode old, JsonNode now) {
        JsonNode before = old.get("additionalProperties");
        JsonNode after = now.get("additionalProperties");
        if (direction == REQUEST && !isFalse(before) && isFalse(after)) {
            record(Rule.REQUEST_ADDITIONAL_PROPERTIES_REJECTED, at, "");
        }
        if (before != null && before.isObject() && after != null && after.isObject()) {
            compareSchema(direction, at + "{}", before, after);
        }
    }

    private static boolean isFalse(JsonNode node) {
        return node != null && node.isBoolean() && !node.asBoolean();
    }

    // ---- problems -------------------------------------------------------------------------------------------------

    private void compareProblems() {
        Map<String, ProblemCatalog.Problem> before = ProblemCatalog.read(baseline);
        Map<String, ProblemCatalog.Problem> after = ProblemCatalog.read(current);
        before.forEach((code, problem) -> {
            String at = "problem " + code;
            ProblemCatalog.Problem replacement = after.get(code);
            if (replacement == null) {
                record(Rule.PROBLEM_CODE_REMOVED, at, "");
                return;
            }
            if (!problem.statuses().equals(replacement.statuses())) {
                record(Rule.PROBLEM_STATUS_CHANGED, at, problem.statuses() + " -> " + replacement.statuses());
            }
            if (!problem.types().equals(replacement.types())) {
                record(Rule.PROBLEM_TYPE_CHANGED, at, problem.types() + " -> " + replacement.types());
            }
            problem.members().stream().filter(member -> !replacement.members().contains(member))
                    .forEach(member -> record(Rule.PROBLEM_MEMBER_REMOVED, at + " member " + member, ""));
            replacement.members().stream().filter(member -> !problem.members().contains(member))
                    .forEach(member -> record(Rule.PROBLEM_MEMBER_ADDED, at + " member " + member, ""));
        });
        after.keySet().stream().filter(code -> !before.containsKey(code))
                .forEach(code -> record(Rule.PROBLEM_CODE_ADDED, "problem " + code, ""));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void record(Rule rule, String location, String detail) {
        Change change = new Change(rule, location, detail);
        changes.putIfAbsent(change.key(), change);
    }

    private List<Change> sorted() {
        return changes.values().stream().sorted(Change.ORDER).toList();
    }

    private static JsonNode definitionReference(String name) {
        return JsonNodeFactory.instance.objectNode().put("$ref", "#/$defs/" + name.replace("~", "~0").replace("/", "~1"));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean deprecated(JsonNode node) {
        return node != null && node.path("deprecated").asBoolean(false);
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        if (array.isArray()) {
            array.forEach(each -> names.add(each.asText()));
        }
        return names;
    }

    private static Map<String, JsonNode> byLowerCaseName(JsonNode node) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (String name : fieldNames(node)) {
            byName.put(name.toLowerCase(Locale.ROOT), node.get(name));
        }
        return byName;
    }

    private static Map<String, String> lowerCaseNames(JsonNode node) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String name : fieldNames(node)) {
            names.put(name.toLowerCase(Locale.ROOT), name);
        }
        return names;
    }

    private static String describe(Object value) {
        return value == null ? "none" : value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : value.toString();
    }

    private static String describeTypes(Set<String> types) {
        return types.isEmpty() ? "any" : String.join("|", types);
    }
}
