package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One small synthetic pair of documents for every rule of ADR-022: a baseline and a current document that differ in exactly one
 * way, and the one change the check must report for it, with its class. A rule without a case here fails
 * {@link #everyRuleHasACase()}.
 */
class CompatibilityRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** POST /api/v1/things/{id} (key needed, two schemas, a 400 problem) and GET /api/v1/notes (no key, no body). */
    private static final String OPENAPI = """
            {
              "openapi": "3.1.0",
              "info": {"title": "Synthetic", "version": "v1"},
              "security": [{"ApiKeyAuth": []}],
              "components": {
                "securitySchemes": {
                  "ApiKeyAuth": {"type": "apiKey", "in": "header", "name": "X-API-Key"},
                  "Other": {"type": "apiKey", "in": "header", "name": "X-Other-Key"}
                },
                "schemas": {
                  "ThingRequest": {
                    "type": "object",
                    "required": ["name"],
                    "properties": {
                      "name": {"type": "string", "minLength": 1, "maxLength": 10, "pattern": "^[a-z]+$"},
                      "note": {"type": ["string", "null"]},
                      "count": {"type": "integer", "format": "int32", "maximum": 5},
                      "color": {"type": "string", "enum": ["RED", "BLUE"]},
                      "label": {"type": "string"},
                      "tags": {"type": "array", "items": {"type": "string"}, "maxItems": 3}
                    }
                  },
                  "Thing": {
                    "type": "object",
                    "required": ["id"],
                    "properties": {
                      "id": {"type": "string", "format": "uuid"},
                      "name": {"type": "string"},
                      "level": {"type": "string", "enum": ["LOW", "HIGH"]},
                      "label": {"type": "string"},
                      "score": {"type": "number"},
                      "count": {"type": "integer"},
                      "child": {"anyOf": [{"$ref": "#/components/schemas/Child"}, {"type": "null"}]},
                      "other": {"$ref": "#/components/schemas/Child"},
                      "shape": {"oneOf": [{"$ref": "#/components/schemas/Circle"}, {"$ref": "#/components/schemas/Square"}]},
                      "values": {"type": "object", "additionalProperties": {"type": "string"}}
                    }
                  },
                  "Child": {"type": "object", "properties": {"value": {"type": "string"}}},
                  "Circle": {"type": "object", "properties": {"radius": {"type": "number"}}},
                  "Square": {"type": "object", "properties": {"side": {"type": "number"}}},
                  "ProblemDetail": {"type": "object", "properties": {"type": {"type": "string"}, "status": {"type": "integer"}}}
                }
              },
              "paths": {
                "/api/v1/things/{id}": {
                  "post": {
                    "operationId": "createThing",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}},
                      {"name": "mode", "in": "query", "required": false, "schema": {"type": "string"}},
                      {"name": "page", "in": "query", "required": true, "schema": {"type": "integer"}}
                    ],
                    "requestBody": {
                      "required": true,
                      "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ThingRequest"}}}
                    },
                    "responses": {
                      "200": {
                        "description": "ok",
                        "headers": {"X-Trace": {"schema": {"type": "string"}}},
                        "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Thing"}}}
                      },
                      "304": {"description": "not modified"},
                      "400": {
                        "description": "bad request",
                        "content": {
                          "application/problem+json": {
                            "schema": {"$ref": "#/components/schemas/ProblemDetail"},
                            "examples": {
                              "invalid-request": {
                                "value": {
                                  "type": "urn:listing-guard:problem:invalid-request",
                                  "code": "invalid-request",
                                  "status": 400,
                                  "title": "Bad Request",
                                  "detail": "The request body is missing."
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                "/api/v1/notes": {
                  "get": {
                    "operationId": "listNotes",
                    "security": [],
                    "responses": {"200": {"description": "ok", "content": {"application/json": {"schema": {"type": "string"}}}}}
                  }
                }
              }
            }
            """;

    private static final String INPUT_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "$defs": {
                "Listing": {"type": "object", "properties": {"title": {"type": "string"}}},
                "Price": {"type": "object", "properties": {"amount": {"type": "number"}}}
              }
            }
            """;

    private static final String OP = "/paths/~1api~1v1~1things~1{id}/post";
    private static final String NOTES = "/paths/~1api~1v1~1notes/get";
    private static final String REQ = "/components/schemas/ThingRequest";
    private static final String RES = "/components/schemas/Thing";
    private static final String PROBLEM = OP + "/responses/400/content/application~1problem+json/examples";
    private static final String THINGS = "POST /api/v1/things/{id}";
    private static final String UNKNOWN_THING = """
            {"value": {"type": "urn:listing-guard:problem:unknown-thing", "code": "unknown-thing", "status": 400,
                       "title": "Bad Request", "detail": "No such thing."}}""";

    /** A pair of documents: the baseline with {@code before} applied and the current one with {@code after} applied. */
    record Case(Rule rule, ChangeClass expected, String location, boolean input, Consumer<ObjectNode> before, Consumer<ObjectNode> after) {

        @Override
        public String toString() {
            return rule.id() + " (" + expected.label() + ")";
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // operations
                additive(Rule.OPERATION_ADDED, "GET /api/v1/notes", doc -> remove(doc, "/paths/~1api~1v1~1notes"), doc -> { }),
                breaking(Rule.OPERATION_REMOVED, "GET /api/v1/notes", doc -> { }, doc -> remove(doc, "/paths/~1api~1v1~1notes")),
                breaking(Rule.OPERATION_ID_CHANGED, THINGS, doc -> { }, doc -> put(doc, OP + "/operationId", "\"createThing_1\"")),
                nonBreaking(Rule.OPERATION_DEPRECATED, THINGS, doc -> { }, doc -> put(doc, OP + "/deprecated", "true")),
                breaking(Rule.SECURITY_ADDED, "GET /api/v1/notes", doc -> { }, doc -> remove(doc, NOTES + "/security")),
                breaking(Rule.SECURITY_CHANGED, THINGS, doc -> { }, doc -> put(doc, OP + "/security", "[{\"Other\": []}]")),
                nonBreaking(Rule.SECURITY_REMOVED, THINGS, doc -> { }, doc -> put(doc, OP + "/security", "[]")),
                // parameters
                additive(Rule.OPTIONAL_PARAMETER_ADDED, THINGS + " parameter query:mode", doc -> remove(doc, OP + "/parameters/1"), doc -> { }),
                breaking(Rule.REQUIRED_PARAMETER_ADDED, THINGS + " parameter query:page", doc -> remove(doc, OP + "/parameters/2"), doc -> { }),
                breaking(Rule.PARAMETER_REMOVED, THINGS + " parameter query:mode", doc -> { }, doc -> remove(doc, OP + "/parameters/1")),
                breaking(Rule.PARAMETER_BECAME_REQUIRED, THINGS + " parameter query:mode", doc -> { },
                        doc -> put(doc, OP + "/parameters/1/required", "true")),
                nonBreaking(Rule.PARAMETER_BECAME_OPTIONAL, THINGS + " parameter query:page", doc -> { },
                        doc -> put(doc, OP + "/parameters/2/required", "false")),
                nonBreaking(Rule.PARAMETER_DEPRECATED, THINGS + " parameter query:mode", doc -> { },
                        doc -> put(doc, OP + "/parameters/1/deprecated", "true")),
                // request body
                additive(Rule.OPTIONAL_REQUEST_BODY_ADDED, "GET /api/v1/notes request", doc -> { },
                        doc -> put(doc, NOTES + "/requestBody", "{\"content\": {\"application/json\": {\"schema\": {\"type\": \"string\"}}}}")),
                breaking(Rule.REQUIRED_REQUEST_BODY_ADDED, "GET /api/v1/notes request", doc -> { }, doc -> put(doc, NOTES + "/requestBody",
                        "{\"required\": true, \"content\": {\"application/json\": {\"schema\": {\"type\": \"string\"}}}}")),
                breaking(Rule.REQUEST_BODY_REMOVED, THINGS + " request", doc -> { }, doc -> remove(doc, OP + "/requestBody")),
                breaking(Rule.REQUEST_BODY_BECAME_REQUIRED, THINGS + " request", doc -> put(doc, OP + "/requestBody/required", "false"),
                        doc -> { }),
                nonBreaking(Rule.REQUEST_BODY_BECAME_OPTIONAL, THINGS + " request", doc -> { },
                        doc -> put(doc, OP + "/requestBody/required", "false")),
                additive(Rule.REQUEST_MEDIA_TYPE_ADDED, THINGS + " request application/xml", doc -> { },
                        doc -> put(doc, OP + "/requestBody/content/application~1xml", "{\"schema\": {\"type\": \"string\"}}")),
                breaking(Rule.REQUEST_MEDIA_TYPE_REMOVED, THINGS + " request application/xml",
                        doc -> put(doc, OP + "/requestBody/content/application~1xml", "{\"schema\": {\"type\": \"string\"}}"), doc -> { }),
                // responses
                breaking(Rule.SUCCESS_RESPONSE_ADDED, THINGS + " response 202", doc -> { },
                        doc -> put(doc, OP + "/responses/202", "{\"description\": \"accepted\"}")),
                breaking(Rule.SUCCESS_RESPONSE_REMOVED, "GET /api/v1/notes response 200", doc -> { },
                        doc -> remove(doc, NOTES + "/responses/200")),
                additive(Rule.ERROR_RESPONSE_ADDED, THINGS + " response 503", doc -> { },
                        doc -> put(doc, OP + "/responses/503", "{\"description\": \"unavailable\"}")),
                nonBreaking(Rule.ERROR_RESPONSE_REMOVED, THINGS + " response 304", doc -> { }, doc -> remove(doc, OP + "/responses/304")),
                additive(Rule.RESPONSE_MEDIA_TYPE_ADDED, THINGS + " response 200 text/csv", doc -> { },
                        doc -> put(doc, OP + "/responses/200/content/text~1csv", "{\"schema\": {\"type\": \"string\"}}")),
                breaking(Rule.RESPONSE_MEDIA_TYPE_REMOVED, THINGS + " response 200 text/csv",
                        doc -> put(doc, OP + "/responses/200/content/text~1csv", "{\"schema\": {\"type\": \"string\"}}"), doc -> { }),
                additive(Rule.RESPONSE_HEADER_ADDED, THINGS + " response 200 header X-Other", doc -> { },
                        doc -> put(doc, OP + "/responses/200/headers/X-Other", "{\"schema\": {\"type\": \"string\"}}")),
                breaking(Rule.RESPONSE_HEADER_REMOVED, THINGS + " response 200 header X-Trace", doc -> { },
                        doc -> remove(doc, OP + "/responses/200/headers/X-Trace")),
                // request schemas
                additive(Rule.REQUEST_OPTIONAL_PROPERTY_ADDED, "schema ThingRequest.extra", doc -> { },
                        doc -> put(doc, REQ + "/properties/extra", "{\"type\": \"string\"}")),
                breaking(Rule.REQUEST_REQUIRED_PROPERTY_ADDED, "schema ThingRequest.extra", doc -> { }, doc -> {
                    put(doc, REQ + "/properties/extra", "{\"type\": \"string\"}");
                    put(doc, REQ + "/required", "[\"name\", \"extra\"]");
                }),
                breaking(Rule.REQUEST_PROPERTY_REMOVED, "schema ThingRequest.label", doc -> { }, doc -> remove(doc, REQ + "/properties/label")),
                breaking(Rule.REQUEST_PROPERTY_BECAME_REQUIRED, "schema ThingRequest.label", doc -> { },
                        doc -> put(doc, REQ + "/required", "[\"name\", \"label\"]")),
                nonBreaking(Rule.REQUEST_PROPERTY_BECAME_OPTIONAL, "schema ThingRequest.name", doc -> { }, doc -> put(doc, REQ + "/required", "[]")),
                breaking(Rule.REQUEST_TYPE_CHANGED, "schema ThingRequest.label", doc -> { },
                        doc -> put(doc, REQ + "/properties/label/type", "\"integer\"")),
                nonBreaking(Rule.REQUEST_TYPE_WIDENED, "schema ThingRequest.count", doc -> { },
                        doc -> put(doc, REQ + "/properties/count/type", "\"number\"")),
                breaking(Rule.REQUEST_NULL_REJECTED, "schema ThingRequest.note", doc -> { },
                        doc -> put(doc, REQ + "/properties/note/type", "\"string\"")),
                nonBreaking(Rule.REQUEST_NULL_ACCEPTED, "schema ThingRequest.label", doc -> { },
                        doc -> put(doc, REQ + "/properties/label/type", "[\"string\", \"null\"]")),
                additive(Rule.REQUEST_ENUM_VALUE_ADDED, "schema ThingRequest.color enum GREEN", doc -> { },
                        doc -> put(doc, REQ + "/properties/color/enum", "[\"RED\", \"BLUE\", \"GREEN\"]")),
                breaking(Rule.REQUEST_ENUM_VALUE_REMOVED, "schema ThingRequest.color enum BLUE", doc -> { },
                        doc -> put(doc, REQ + "/properties/color/enum", "[\"RED\"]")),
                breaking(Rule.REQUEST_ENUM_ADDED, "schema ThingRequest.label", doc -> { },
                        doc -> put(doc, REQ + "/properties/label/enum", "[\"A\", \"B\"]")),
                nonBreaking(Rule.REQUEST_ENUM_REMOVED, "schema ThingRequest.color", doc -> { },
                        doc -> remove(doc, REQ + "/properties/color/enum")),
                breaking(Rule.REQUEST_FORMAT_CHANGED, "schema ThingRequest.count", doc -> { },
                        doc -> put(doc, REQ + "/properties/count/format", "\"int64\"")),
                breaking(Rule.REQUEST_CONSTRAINT_TIGHTENED, "schema ThingRequest.name maxLength", doc -> { },
                        doc -> put(doc, REQ + "/properties/name/maxLength", "5")),
                nonBreaking(Rule.REQUEST_CONSTRAINT_RELAXED, "schema ThingRequest.name maxLength", doc -> { },
                        doc -> put(doc, REQ + "/properties/name/maxLength", "20")),
                breaking(Rule.REQUEST_ADDITIONAL_PROPERTIES_REJECTED, "schema ThingRequest", doc -> { },
                        doc -> put(doc, REQ + "/additionalProperties", "false")),
                // response schemas
                additive(Rule.RESPONSE_PROPERTY_ADDED, "schema Thing.extra", doc -> { },
                        doc -> put(doc, RES + "/properties/extra", "{\"type\": \"string\"}")),
                breaking(Rule.RESPONSE_PROPERTY_REMOVED, "schema Thing.label", doc -> { }, doc -> remove(doc, RES + "/properties/label")),
                breaking(Rule.RESPONSE_PROPERTY_BECAME_OPTIONAL, "schema Thing.id", doc -> { }, doc -> put(doc, RES + "/required", "[]")),
                nonBreaking(Rule.RESPONSE_PROPERTY_BECAME_REQUIRED, "schema Thing.name", doc -> { },
                        doc -> put(doc, RES + "/required", "[\"id\", \"name\"]")),
                breaking(Rule.RESPONSE_TYPE_CHANGED, "schema Thing.count", doc -> { },
                        doc -> put(doc, RES + "/properties/count/type", "\"number\"")),
                nonBreaking(Rule.RESPONSE_TYPE_NARROWED, "schema Thing.score", doc -> { },
                        doc -> put(doc, RES + "/properties/score/type", "\"integer\"")),
                breaking(Rule.RESPONSE_BECAME_NULLABLE, "schema Thing.name", doc -> { },
                        doc -> put(doc, RES + "/properties/name/type", "[\"string\", \"null\"]")),
                nonBreaking(Rule.RESPONSE_NO_LONGER_NULLABLE, "schema Thing.child", doc -> { },
                        doc -> put(doc, RES + "/properties/child", "{\"$ref\": \"#/components/schemas/Child\"}")),
                additive(Rule.RESPONSE_ENUM_VALUE_ADDED, "schema Thing.level enum UNKNOWN", doc -> { },
                        doc -> put(doc, RES + "/properties/level/enum", "[\"LOW\", \"HIGH\", \"UNKNOWN\"]")),
                nonBreaking(Rule.RESPONSE_ENUM_VALUE_REMOVED, "schema Thing.level enum HIGH", doc -> { },
                        doc -> put(doc, RES + "/properties/level/enum", "[\"LOW\"]")),
                breaking(Rule.RESPONSE_ENUM_REMOVED, "schema Thing.level", doc -> { }, doc -> remove(doc, RES + "/properties/level/enum")),
                nonBreaking(Rule.RESPONSE_ENUM_ADDED, "schema Thing.label", doc -> { },
                        doc -> put(doc, RES + "/properties/label/enum", "[\"A\", \"B\"]")),
                breaking(Rule.RESPONSE_FORMAT_CHANGED, "schema Thing.id", doc -> { }, doc -> remove(doc, RES + "/properties/id/format")),
                // either side
                nonBreaking(Rule.PROPERTY_DEPRECATED, "schema Thing.label", doc -> { },
                        doc -> put(doc, RES + "/properties/label/deprecated", "true")),
                nonBreaking(Rule.SCHEMA_RENAMED, "schema Thing.other", doc -> { }, doc -> {
                    put(doc, "/components/schemas/Kid", "{\"type\": \"object\", \"properties\": {\"value\": {\"type\": \"string\"}}}");
                    put(doc, RES + "/properties/other", "{\"$ref\": \"#/components/schemas/Kid\"}");
                }),
                breaking(Rule.SCHEMA_UNION_CHANGED, "schema Thing.shape", doc -> { }, doc -> {
                    put(doc, "/components/schemas/Triangle", "{\"type\": \"object\", \"properties\": {\"base\": {\"type\": \"number\"}}}");
                    put(doc, RES + "/properties/shape", "{\"oneOf\": [{\"$ref\": \"#/components/schemas/Circle\"}, "
                            + "{\"$ref\": \"#/components/schemas/Square\"}, {\"$ref\": \"#/components/schemas/Triangle\"}]}");
                }),
                // problems
                additive(Rule.PROBLEM_CODE_ADDED, "problem unknown-thing", doc -> { }, doc -> put(doc, PROBLEM + "/unknown-thing", UNKNOWN_THING)),
                breaking(Rule.PROBLEM_CODE_REMOVED, "problem unknown-thing", doc -> put(doc, PROBLEM + "/unknown-thing", UNKNOWN_THING),
                        doc -> { }),
                breaking(Rule.PROBLEM_STATUS_CHANGED, "problem invalid-request", doc -> { },
                        doc -> put(doc, PROBLEM + "/invalid-request/value/status", "422")),
                breaking(Rule.PROBLEM_TYPE_CHANGED, "problem invalid-request", doc -> { },
                        doc -> put(doc, PROBLEM + "/invalid-request/value/type", "\"urn:listing-guard:problem:bad-request\"")),
                additive(Rule.PROBLEM_MEMBER_ADDED, "problem invalid-request member errors", doc -> { },
                        doc -> put(doc, PROBLEM + "/invalid-request/value/errors", "[]")),
                breaking(Rule.PROBLEM_MEMBER_REMOVED, "problem invalid-request member detail", doc -> { },
                        doc -> remove(doc, PROBLEM + "/invalid-request/value/detail")),
                // input schema
                input(Rule.INPUT_DEFINITION_ADDED, ChangeClass.ADDITIVE, "schema Price", doc -> remove(doc, "/$defs/Price"), doc -> { }),
                input(Rule.INPUT_DEFINITION_REMOVED, ChangeClass.BREAKING, "schema Price", doc -> { }, doc -> remove(doc, "/$defs/Price")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void eachRuleReportsItsOneChangeWithItsClass(Case example) throws Exception {
        ObjectNode baseline = (ObjectNode) MAPPER.readTree(example.input() ? INPUT_SCHEMA : OPENAPI);
        ObjectNode current = baseline.deepCopy();
        example.before().accept(baseline);
        example.after().accept(current);

        List<Change> changes = example.input()
                ? CompatibilityChecker.compareInputSchemas(baseline, current)
                : CompatibilityChecker.compareOpenApi(baseline, current);

        assertThat(changes).extracting(Change::key).containsExactly(example.rule().id() + " @ " + example.location());
        assertThat(changes.getFirst().changeClass()).isEqualTo(example.expected());
    }

    @Test
    void theUnchangedDocumentsHaveNoChange() throws Exception {
        assertThat(CompatibilityChecker.compareOpenApi(MAPPER.readTree(OPENAPI), MAPPER.readTree(OPENAPI))).isEmpty();
        assertThat(CompatibilityChecker.compareInputSchemas(MAPPER.readTree(INPUT_SCHEMA), MAPPER.readTree(INPUT_SCHEMA))).isEmpty();
    }

    @Test
    void everyRuleHasACase() {
        Set<Rule> covered = cases().map(Case::rule).collect(Collectors.toCollection(() -> EnumSet.noneOf(Rule.class)));
        assertThat(covered).as("a rule of ADR-022 without a synthetic case in this test")
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(Rule.values()));
        assertThat(cases().map(Case::rule).count()).as("one case per rule").isEqualTo(Rule.values().length);
    }

    // ---- case builders: each checks that the rule has the class the case expects ---------------------------------

    private static Case breaking(Rule rule, String location, Consumer<ObjectNode> before, Consumer<ObjectNode> after) {
        return of(rule, ChangeClass.BREAKING, location, false, before, after);
    }

    private static Case additive(Rule rule, String location, Consumer<ObjectNode> before, Consumer<ObjectNode> after) {
        return of(rule, ChangeClass.ADDITIVE, location, false, before, after);
    }

    private static Case nonBreaking(Rule rule, String location, Consumer<ObjectNode> before, Consumer<ObjectNode> after) {
        return of(rule, ChangeClass.NON_BREAKING, location, false, before, after);
    }

    private static Case input(Rule rule, ChangeClass expected, String location, Consumer<ObjectNode> before, Consumer<ObjectNode> after) {
        return of(rule, expected, location, true, before, after);
    }

    private static Case of(Rule rule, ChangeClass expected, String location, boolean input, Consumer<ObjectNode> before,
                           Consumer<ObjectNode> after) {
        return new Case(rule, expected, location, input, before, after);
    }

    // ---- JSON pointer edits ---------------------------------------------------------------------------------------

    static void put(ObjectNode document, String pointer, String json) {
        JsonPointer path = JsonPointer.compile(pointer);
        JsonNode parent = document.at(path.head());
        JsonNode value;
        try {
            value = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
        if (parent instanceof ArrayNode array) {
            array.set(path.last().getMatchingIndex(), value);
        } else if (parent instanceof ObjectNode object) {
            object.set(path.last().getMatchingProperty(), value);
        } else {
            throw new IllegalArgumentException("No parent for " + pointer);
        }
    }

    static void remove(ObjectNode document, String pointer) {
        JsonPointer path = JsonPointer.compile(pointer);
        JsonNode parent = document.at(path.head());
        if (parent instanceof ArrayNode array) {
            array.remove(path.last().getMatchingIndex());
        } else if (parent instanceof ObjectNode object && object.has(path.last().getMatchingProperty())) {
            object.remove(path.last().getMatchingProperty());
        } else {
            throw new IllegalArgumentException("Nothing at " + pointer);
        }
    }
}
