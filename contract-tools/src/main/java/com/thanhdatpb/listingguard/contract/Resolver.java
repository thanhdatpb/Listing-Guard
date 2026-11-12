package com.thanhdatpb.listingguard.contract;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

/** Follows local {@code $ref}s ({@code #/components/...}, {@code #/$defs/...}) inside one document. */
final class Resolver {

    private static final int MAX_HOPS = 32;

    /** A schema after its references were followed, and the name of the last component it pointed at (or null). */
    record Resolved(JsonNode node, String name) {
    }

    private final JsonNode root;

    Resolver(JsonNode root) {
        this.root = root;
    }

    JsonNode root() {
        return root;
    }

    Resolved resolve(JsonNode node) {
        JsonNode current = node;
        String name = null;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (current == null || !current.isObject() || !current.has("$ref")) {
                return new Resolved(current, name);
            }
            String ref = current.get("$ref").asText();
            name = lastSegment(ref);
            current = pointer(ref);
        }
        throw new IllegalArgumentException("More than " + MAX_HOPS + " chained $ref hops at " + node);
    }

    private JsonNode pointer(String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Only local references are supported, found: " + ref);
        }
        JsonNode target = root.at(JsonPointer.compile(ref.substring(1)));
        if (target.isMissingNode()) {
            throw new IllegalArgumentException("Unresolvable reference: " + ref);
        }
        return target;
    }

    private static String lastSegment(String ref) {
        String segment = ref.substring(ref.lastIndexOf('/') + 1);
        return segment.replace("~1", "/").replace("~0", "~");
    }
}
