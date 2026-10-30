package com.thanhdatpb.listingguard.rag.llm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Answers with canned text chosen from the request: the first script whose condition matches wins, otherwise
 * the default text. It has no semantics and calls nothing, which is what tests need (no network, stable
 * results). Not for production use.
 */
public final class ScriptedLlmProvider implements LlmProvider {

    private record Script(Predicate<LlmRequest> condition, Function<LlmRequest, LlmResponse> reply) {
    }

    private final String modelId;
    private final String defaultText;
    private final List<Script> scripts = new CopyOnWriteArrayList<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    public ScriptedLlmProvider(String modelId, String defaultText) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (defaultText == null) {
            throw new IllegalArgumentException("defaultText is required");
        }
        this.modelId = modelId;
        this.defaultText = defaultText;
    }

    /** Answers {@code {"findings":[]}} unless a script matches. */
    public static ScriptedLlmProvider empty() {
        return new ScriptedLlmProvider("scripted:v1", "{\"findings\":[]}");
    }

    /** When {@code condition} matches, answer with {@code text} and finish reason {@code stop}. */
    public ScriptedLlmProvider when(Predicate<LlmRequest> condition, String text) {
        scripts.add(new Script(condition, request -> answer(request, text)));
        return this;
    }

    /** When the user message contains {@code fragment}, answer with {@code text}. */
    public ScriptedLlmProvider whenUserContains(String fragment, String text) {
        return when(request -> request.user().contains(fragment), text);
    }

    /** When {@code condition} matches, fail with {@code failure}. */
    public ScriptedLlmProvider failWhen(Predicate<LlmRequest> condition, LlmException failure) {
        scripts.add(new Script(condition, request -> {
            throw failure;
        }));
        return this;
    }

    /** Every request this provider has received, oldest first. */
    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        for (Script script : scripts) {
            if (script.condition().test(request)) {
                return script.reply().apply(request);
            }
        }
        return answer(request, defaultText);
    }

    private static LlmResponse answer(LlmRequest request, String text) {
        int in = (request.system().length() + request.user().length()) / 4;
        return new LlmResponse(text, in, text.length() / 4, "stop");
    }
}
