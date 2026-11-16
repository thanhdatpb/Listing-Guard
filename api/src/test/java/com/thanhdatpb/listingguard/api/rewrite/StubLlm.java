package com.thanhdatpb.listingguard.api.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A language model for tests (ADR-037): it answers with the text a test gave it, or fails, and remembers every request. It calls
 * nothing. The static helpers write the JSON a model would answer with, in the shape of {@code RewritePrompt.SCHEMA}.
 */
public final class StubLlm implements LlmProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NONE = "{\"candidates\":[]}";

    private volatile String answer = NONE;
    private volatile LlmException failure;
    private volatile int generation;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    /**
     * The shared client caches answers by model id and request, so every change of the stub's behaviour is a new "model": a test never
     * gets the answer an earlier test made the same request produce.
     */
    @Override
    public String modelId() {
        return "stub:v" + generation;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        String text = answer;
        return new LlmResponse(text, 10, text.length() / 4, "stop");
    }

    /** From now on answer with {@code text} (usually from {@link #answer}). */
    public void reply(String text) {
        failure = null;
        answer = text;
        generation++;
    }

    public void fail(LlmException exception) {
        failure = exception;
        generation++;
    }

    /** No candidates, no failure, no remembered request. */
    public void reset() {
        generation++;
        failure = null;
        answer = NONE;
        requests.clear();
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    /** One candidate: a new title (or null to keep it) and the tags it replaces. */
    public static ObjectNode candidate(String title, ObjectNode... tagChanges) {
        ObjectNode node = JSON.createObjectNode();
        if (title == null) {
            node.putNull("title");
        } else {
            node.put("title", title);
        }
        ArrayNode tags = node.putArray("tags");
        for (ObjectNode change : tagChanges) {
            tags.add(change);
        }
        return node;
    }

    public static ObjectNode tag(int index, String from, String to) {
        ObjectNode node = JSON.createObjectNode();
        node.put("index", index);
        node.put("from", from);
        node.put("to", to);
        return node;
    }

    /** The whole answer of the model: these candidates, best first. */
    public static String answer(ObjectNode... candidates) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = root.putArray("candidates");
        for (ObjectNode candidate : candidates) {
            array.add(candidate);
        }
        return root.toString();
    }
}
