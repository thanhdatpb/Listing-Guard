package com.thanhdatpb.listingguard.rag.eval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.rag.policy.PolicyParseException;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Questions with the sections that should answer them. Every question exists in each language of
 * {@link #LANGUAGES}, so retrieval quality can be reported per language.
 */
public record EvalSet(String marketplace, List<Query> queries) {

    public static final List<String> LANGUAGES = List.of("en", "vi");

    private static final Set<String> TOP_KEYS = Set.of("marketplace", "queries");
    private static final Set<String> QUERY_KEYS = Set.of("id", "en", "vi", "expected");
    private static final Set<String> EXPECTED_KEYS = Set.of("file", "section");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public EvalSet {
        queries = List.copyOf(queries);
    }

    /** @param questions language code to question text */
    public record Query(String id, Map<String, String> questions, List<Expected> expected) {

        public Query {
            questions = Map.copyOf(questions);
            expected = List.copyOf(expected);
        }

        public boolean isRelevant(RetrievedChunk chunk) {
            return expected.stream().anyMatch(e -> e.matches(chunk));
        }
    }

    /**
     * A section that answers a question.
     *
     * @param file    manifest file name of the document
     * @param section start of the heading path inside the document (without the document title), or null for
     *                "anywhere in the document". An empty heading path (text before the first heading) is "".
     */
    public record Expected(String file, String section) {

        public boolean matches(RetrievedChunk chunk) {
            return file.equals(chunk.sourceFile()) && (section == null || headingPath(chunk).startsWith(section));
        }

        static String headingPath(RetrievedChunk chunk) {
            String path = chunk.sectionPath();
            String title = chunk.document();
            if (path.equals(title)) {
                return "";
            }
            return path.startsWith(title + " > ") ? path.substring(title.length() + 3) : path;
        }
    }

    public static EvalSet load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PolicyParseException("Cannot read evaluation set " + file, e);
        }
    }

    public static EvalSet parse(String yamlText) {
        JsonNode root;
        try {
            root = YAML.readTree(yamlText);
        } catch (JsonProcessingException e) {
            throw new PolicyParseException("Invalid evaluation set YAML: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new PolicyParseException("Evaluation set must be a YAML mapping");
        }
        rejectUnknownKeys(root, TOP_KEYS, "evaluation set");
        String marketplace = text(root, "marketplace", "evaluation set").toUpperCase(Locale.ROOT);
        JsonNode queriesNode = root.get("queries");
        if (queriesNode == null || !queriesNode.isArray() || queriesNode.isEmpty()) {
            throw new PolicyParseException("Evaluation set needs a non-empty 'queries' list");
        }
        List<Query> queries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonNode node : queriesNode) {
            Query query = parseQuery(node, "queries[" + index++ + "]");
            if (!ids.add(query.id())) {
                throw new PolicyParseException("Duplicate query id '" + query.id() + "'");
            }
            queries.add(query);
        }
        return new EvalSet(marketplace, queries);
    }

    private static Query parseQuery(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new PolicyParseException(where + " must be a mapping");
        }
        rejectUnknownKeys(node, QUERY_KEYS, where);
        String id = text(node, "id", where);
        Map<String, String> questions = new LinkedHashMap<>();
        for (String language : LANGUAGES) {
            questions.put(language, text(node, language, where + " (" + id + ")"));
        }
        JsonNode expectedNode = node.get("expected");
        if (expectedNode == null || !expectedNode.isArray() || expectedNode.isEmpty()) {
            throw new PolicyParseException(where + " (" + id + ") needs at least one expected section");
        }
        List<Expected> expected = new ArrayList<>();
        for (JsonNode e : expectedNode) {
            if (!e.isObject()) {
                throw new PolicyParseException(where + " (" + id + "): each expected entry must be a mapping");
            }
            rejectUnknownKeys(e, EXPECTED_KEYS, where + " (" + id + ") expected");
            JsonNode section = e.get("section");
            expected.add(new Expected(text(e, "file", where + " (" + id + ") expected"),
                    section == null || section.isNull() ? null : section.asText()));
        }
        return new Query(id, questions, expected);
    }

    private static void rejectUnknownKeys(JsonNode node, Set<String> allowed, String where) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new PolicyParseException(where + " has unknown key '" + name + "'");
            }
        }
    }

    private static String text(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw new PolicyParseException(where + " is missing required key '" + key + "'");
        }
        return value.asText().trim();
    }
}
