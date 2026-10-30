package com.thanhdatpb.listingguard.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScriptedLlmProviderTest {

    private static LlmRequest request(String user) {
        return new LlmRequest("system", user, "{}", 1500, Duration.ofSeconds(30));
    }

    @Test
    void answersTheDefaultWhenNothingMatches() {
        ScriptedLlmProvider provider = ScriptedLlmProvider.empty();

        LlmResponse response = provider.complete(request("anything"));

        assertThat(response.text()).isEqualTo("{\"findings\":[]}");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(provider.modelId()).isEqualTo("scripted:v1");
    }

    @Test
    void theFirstMatchingScriptWins() {
        ScriptedLlmProvider provider = ScriptedLlmProvider.empty()
                .whenUserContains("mug", "first")
                .whenUserContains("mug", "second")
                .whenUserContains("poster", "third");

        assertThat(provider.complete(request("a mug")).text()).isEqualTo("first");
        assertThat(provider.complete(request("a poster")).text()).isEqualTo("third");
        assertThat(provider.complete(request("a lamp")).text()).isEqualTo("{\"findings\":[]}");
    }

    @Test
    void canFailWithTheGivenKind() {
        ScriptedLlmProvider provider = ScriptedLlmProvider.empty().failWhen(
                r -> r.user().contains("slow"), new LlmException(LlmException.Kind.TIMEOUT, "timed out"));

        assertThatThrownBy(() -> provider.complete(request("slow one")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT));
        assertThat(provider.complete(request("fast one")).text()).isEqualTo("{\"findings\":[]}");
    }

    @Test
    void recordsEveryRequest() {
        ScriptedLlmProvider provider = ScriptedLlmProvider.empty();

        provider.complete(request("one"));
        provider.complete(request("two"));

        assertThat(provider.requests()).extracting(LlmRequest::user).containsExactly("one", "two");
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new ScriptedLlmProvider(" ", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("s", "u", "{}", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("s", "u", "{}", 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmResponse(null, 0, 0, "stop")).isInstanceOf(IllegalArgumentException.class);
    }
}
