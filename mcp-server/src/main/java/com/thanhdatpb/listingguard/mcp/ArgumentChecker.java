package com.thanhdatpb.listingguard.mcp;

import java.util.List;
import java.util.Map;

/**
 * Checks the arguments of a call against the tool's own input schema before anything is sent (ADR-017). It looks only at
 * what the schema says at the top level: which names exist, which are required and whether each is a string, an object
 * or an array. Everything inside a value is the API's to judge. A message names the argument, never its value.
 */
final class ArgumentChecker {

    private ArgumentChecker() {
    }

    /** The first problem found, as a sentence for the agent, or {@code null} when the arguments are acceptable. */
    @SuppressWarnings("unchecked")
    static String check(Map<String, Object> schema, Map<String, Object> arguments) {
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());

        for (String name : arguments.keySet()) {
            if (!properties.containsKey(name)) {
                return properties.isEmpty()
                        ? "This tool takes no arguments."
                        : "Unknown argument '" + shown(name) + "'. Allowed: " + String.join(", ", properties.keySet()) + ".";
            }
        }
        for (String name : required) {
            Object value = arguments.get(name);
            if (value == null) {
                return "Missing required argument '" + name + "'.";
            }
            if (value instanceof String s && s.isBlank()) {
                return "Argument '" + name + "' must not be blank.";
            }
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue; // an optional argument sent as null counts as absent
            }
            Map<String, Object> property = (Map<String, Object>) properties.get(entry.getKey());
            String type = (String) property.get("type");
            if (!matches(type, value)) {
                return "Argument '" + entry.getKey() + "' must be " + describe(type) + ".";
            }
        }
        return null;
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            default -> true;
        };
    }

    private static String describe(String type) {
        return switch (type) {
            case "string" -> "a string";
            case "object" -> "a JSON object";
            case "array" -> "a JSON array";
            default -> "of type " + type;
        };
    }

    private static String shown(String name) {
        return ToolResults.cut(name, 40);
    }
}
