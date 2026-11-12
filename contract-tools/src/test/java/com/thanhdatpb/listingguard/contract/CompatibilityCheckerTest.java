package com.thanhdatpb.listingguard.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How the check compares, beyond one rule at a time: what it ignores, how it deduplicates, and the real documents. */
class CompatibilityCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Two operations that share a response schema; Node is recursive; Shared is used in a request and in a response. */
    private static final String DOCUMENT = """
            {
              "openapi": "3.1.0",
              "info": {"title": "Synthetic", "version": "v1", "description": "first"},
              "components": {
                "headers": {"X-Version": {"schema": {"type": "string"}}},
                "schemas": {
                  "Item": {"type": "object", "description": "an item", "properties": {
                    "name": {"type": "string", "example": "mug"},
                    "size": {"type": "integer"}}},
                  "Node": {"type": "object", "properties": {
                    "label": {"type": "string"},
                    "children": {"type": "array", "items": {"$ref": "#/components/schemas/Node"}}}},
                  "Shared": {"type": "object", "properties": {"code": {"type": "string"}, "kept": {"type": "string"}}},
                  "Legacy": {"type": "object", "properties": {"note": {"type": "string", "nullable": false}}},
                  "Composed": {"allOf": [
                    {"$ref": "#/components/schemas/Item"},
                    {"type": "object", "properties": {"extra": {"type": "string"}}}]}
                }
              },
              "paths": {
                "/items/{id}": {
                  "get": {"operationId": "getItem", "summary": "Read one",
                    "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "string"}}],
                    "responses": {"200": {"description": "ok", "content": {"application/json": {
                      "schema": {"$ref": "#/components/schemas/Item"}, "example": {"name": "mug"}}}}}}
                },
                "/items": {
                  "get": {"operationId": "listItems", "responses": {"200": {"description": "ok", "content": {"application/json": {
                    "schema": {"type": "array", "items": {"$ref": "#/components/schemas/Item"}}}}}}},
                  "post": {"operationId": "createItem",
                    "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Shared"}}}},
                    "responses": {"200": {"description": "ok", "content": {"application/json": {
                      "schema": {"$ref": "#/components/schemas/Shared"}}}}}}
                },
                "/tree": {
                  "get": {"operationId": "tree", "responses": {"200": {"description": "ok", "content": {"application/json": {
                    "schema": {"$ref": "#/components/schemas/Node"}}}}}}
                },
                "/legacy": {
                  "get": {"operationId": "legacy", "responses": {"200": {"description": "ok", "content": {"application/json": {
                    "schema": {"$ref": "#/components/schemas/Legacy"}}}}}}
                },
                "/composed": {
                  "get": {"operationId": "composed", "responses": {"200": {"description": "ok", "content": {"application/json": {
                    "schema": {"$ref": "#/components/schemas/Composed"}}}}}}
                }
              }
            }
            """;

    private static ObjectNode document() throws Exception {
        return (ObjectNode) MAPPER.readTree(DOCUMENT);
    }

    @Test
    void descriptionsSummariesExamplesAndInfoAreNotCompared() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.put(current, "/info/description", "\"second\"");
        CompatibilityRulesTest.put(current, "/components/schemas/Item/description", "\"another\"");
        CompatibilityRulesTest.put(current, "/components/schemas/Item/properties/name/example", "\"cup\"");
        CompatibilityRulesTest.put(current, "/paths/~1items~1{id}/get/summary", "\"Read\"");
        CompatibilityRulesTest.put(current, "/paths/~1items~1{id}/get/responses/200/content/application~1json/example", "{}");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).isEmpty();
    }

    @Test
    void renamingAPathParameterIsTheSameUrl() throws Exception {
        ObjectNode current = document();
        JsonNode item = current.with("paths").remove("/items/{id}");
        ((ObjectNode) item.at("/get/parameters/0")).put("name", "itemId");
        current.with("paths").set("/items/{itemId}", item);

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).isEmpty();
    }

    @Test
    void aChangeInASharedSchemaIsReportedOnceAtTheSchema() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.remove(current, "/components/schemas/Item/properties/size");

        // two operations use Item: one report at Item. Composed copies Item's members through allOf, so its own shape lost the
        // member too and says so
        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key)
                .containsExactly("response-property-removed @ schema Composed.size", "response-property-removed @ schema Item.size");
    }

    @Test
    void aRecursiveSchemaIsComparedOnceAndTerminates() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.remove(current, "/components/schemas/Node/properties/label");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key)
                .containsExactly("response-property-removed @ schema Node.label");
    }

    @Test
    void aSchemaUsedInARequestAndAResponseIsCheckedOnBothSides() throws Exception {
        ObjectNode removed = document();
        CompatibilityRulesTest.remove(removed, "/components/schemas/Shared/properties/code");
        assertThat(CompatibilityChecker.compareOpenApi(document(), removed)).extracting(Change::key).containsExactly(
                "request-property-removed @ schema Shared.code", "response-property-removed @ schema Shared.code");

        ObjectNode added = document();
        CompatibilityRulesTest.put(added, "/components/schemas/Shared/properties/more", "{\"type\": \"string\"}");
        assertThat(CompatibilityChecker.compareOpenApi(document(), added)).extracting(Change::key).containsExactly(
                "request-optional-property-added @ schema Shared.more", "response-property-added @ schema Shared.more");
    }

    @Test
    void openApi30NullableIsNullability() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.put(current, "/components/schemas/Legacy/properties/note/nullable", "true");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key)
                .containsExactly("response-became-nullable @ schema Legacy.note");
    }

    @Test
    void theMembersOfAllOfPartsAreMerged() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.remove(current, "/components/schemas/Composed/allOf/1/properties/extra");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key)
                .containsExactly("response-property-removed @ schema Composed.extra");
    }

    @Test
    void aRenamedSchemaIsComparedWithItsNewOneAndTheLocationNamesBoth() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.put(current, "/components/schemas/Tree",
                "{\"type\": \"object\", \"properties\": {\"children\": {\"type\": \"array\", \"items\": {\"$ref\": \"#/components/schemas/Tree\"}}}}");
        CompatibilityRulesTest.put(current, "/paths/~1tree/get/responses/200/content/application~1json/schema",
                "{\"$ref\": \"#/components/schemas/Tree\"}");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key).containsExactly(
                "response-property-removed @ schema Tree (was Node).label",
                "schema-renamed @ GET /tree response 200 application/json $",
                "schema-renamed @ schema Tree (was Node).children[]");
    }

    @Test
    void aSharedHeaderAddedToManyResponsesIsOneChange() throws Exception {
        ObjectNode current = document();
        for (String path : List.of("/paths/~1items~1{id}/get", "/paths/~1items/get", "/paths/~1tree/get")) {
            CompatibilityRulesTest.put(current, path + "/responses/200/headers",
                    "{\"X-Version\": {\"$ref\": \"#/components/headers/X-Version\"}}");
        }

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::key)
                .containsExactly("response-header-added @ header X-Version (shared)");
    }

    @Test
    void breakingChangesComeFirstThenAdditiveThenTheRest() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.put(current, "/paths/~1legacy/get/deprecated", "true");
        CompatibilityRulesTest.put(current, "/components/schemas/Item/properties/color", "{\"type\": \"string\"}");
        CompatibilityRulesTest.remove(current, "/paths/~1composed");

        assertThat(CompatibilityChecker.compareOpenApi(document(), current)).extracting(Change::changeClass)
                .containsExactly(ChangeClass.BREAKING, ChangeClass.ADDITIVE, ChangeClass.NON_BREAKING);
    }

    @Test
    void aReferenceOutsideTheDocumentIsRefused() throws Exception {
        ObjectNode current = document();
        CompatibilityRulesTest.put(current, "/paths/~1tree/get/responses/200/content/application~1json/schema",
                "{\"$ref\": \"other.json#/Node\"}");

        assertThatThrownBy(() -> CompatibilityChecker.compareOpenApi(document(), current))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only local references");
    }

    @Test
    void inputSchemaDefinitionsAreRequests() throws Exception {
        JsonNode before = MAPPER.readTree("""
                {"$defs": {"Listing": {"type": "object", "properties": {"title": {"type": "string"}, "price": {"$ref": "#/$defs/Price"}}},
                           "Price": {"type": "object", "properties": {"amount": {"type": "number"}}}}}""");
        JsonNode after = MAPPER.readTree("""
                {"$defs": {"Listing": {"type": "object", "properties": {"price": {"$ref": "#/$defs/Price"}, "brand": {"type": "string"}}},
                           "Price": {"type": "object", "properties": {"amount": {"type": "integer"}}}}}""");

        assertThat(CompatibilityChecker.compareInputSchemas(before, after)).extracting(Change::key).containsExactly(
                "request-property-removed @ schema Listing.title",
                "request-type-changed @ schema Price.amount",
                "request-optional-property-added @ schema Listing.brand");
    }

    @Test
    void theCommittedContractComparedWithItselfHasNoChange() throws Exception {
        JsonNode openApi = MAPPER.readTree(Files.readString(Path.of("..", "docs", "api", "openapi.json"), StandardCharsets.UTF_8));
        JsonNode input = MAPPER.readTree(Files.readString(Path.of("..", "docs", "api", "input-schema.json"), StandardCharsets.UTF_8));

        assertThat(CompatibilityChecker.compareOpenApi(openApi, openApi)).isEmpty();
        assertThat(CompatibilityChecker.compareInputSchemas(input, input)).isEmpty();
    }
}
