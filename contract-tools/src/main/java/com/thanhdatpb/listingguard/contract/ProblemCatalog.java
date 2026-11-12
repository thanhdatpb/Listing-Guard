package com.thanhdatpb.listingguard.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The problem codes a document shows, read from the examples of its {@code application/problem+json} responses: for each
 * {@code code}, the statuses and {@code type}s it is shown with and every member of its examples. The API generates an example
 * for each documented error, so this is the error table of the contract in machine form (ADR-022, section 1).
 */
final class ProblemCatalog {

    static final String MEDIA_TYPE = "application/problem+json";

    /** What one code looks like across every example that shows it. */
    record Problem(Set<String> statuses, Set<String> types, Set<String> members) {
    }

    private ProblemCatalog() {
    }

    static Map<String, Problem> read(Resolver document) {
        Map<String, Problem> problems = new TreeMap<>();
        for (JsonNode pathItem : iterable(document.root().path("paths"))) {
            for (JsonNode operation : iterable(pathItem)) {
                JsonNode responses = operation.path("responses");
                for (String status : CompatibilityChecker.fieldNames(responses)) {
                    JsonNode response = document.resolve(responses.get(status)).node();
                    JsonNode media = response == null ? null : response.path("content").get(MEDIA_TYPE);
                    if (media == null) {
                        continue;
                    }
                    for (JsonNode example : examples(document, media)) {
                        add(problems, status, example);
                    }
                }
            }
        }
        return problems;
    }

    private static List<JsonNode> examples(Resolver document, JsonNode media) {
        List<JsonNode> values = new ArrayList<>();
        if (media.has("example")) {
            values.add(media.get("example"));
        }
        for (JsonNode raw : iterable(media.path("examples"))) {
            JsonNode example = document.resolve(raw).node();
            if (example != null && example.has("value")) {
                values.add(example.get("value"));
            }
        }
        return values;
    }

    private static void add(Map<String, Problem> problems, String responseStatus, JsonNode value) {
        String code = CompatibilityChecker.text(value, "code");
        if (code == null) {
            return;
        }
        Problem problem = problems.computeIfAbsent(code, key -> new Problem(new TreeSet<>(), new TreeSet<>(), new TreeSet<>()));
        problem.statuses().add(value.has("status") ? value.get("status").asText() : responseStatus);
        String type = CompatibilityChecker.text(value, "type");
        if (type != null) {
            problem.types().add(type);
        }
        problem.members().addAll(CompatibilityChecker.fieldNames(value));
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node != null && node.isObject()) {
            node.elements().forEachRemaining(each -> {
                if (each.isObject()) {
                    values.add(each);
                }
            });
        }
        return values;
    }
}
