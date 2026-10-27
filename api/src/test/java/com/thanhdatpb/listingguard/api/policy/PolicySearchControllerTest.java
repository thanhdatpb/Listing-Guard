package com.thanhdatpb.listingguard.api.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PolicySearchControllerTest {

    private static PolicySearchController controller(boolean enabled, PolicyRetriever retriever) {
        PolicyProperties properties = new PolicyProperties("policies", new PolicyProperties.Chunking(1500, 200),
                new PolicyProperties.SearchEndpoint(enabled));
        return new PolicySearchController(retriever, properties);
    }

    @Test
    void answersNotFoundAndNeverSearchesWhileTheEndpointIsDisabled() {
        PolicyRetriever mustNotBeCalled = (marketplace, query, topK) -> {
            throw new AssertionError("retriever must not be called");
        };

        assertThatThrownBy(() -> controller(false, mustNotBeCalled).search("ETSY", "anything", 5))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delegatesToTheRetrieverWhenEnabled() {
        PolicyRetriever.RetrievedChunk chunk = new PolicyRetriever.RetrievedChunk(1, 0.9, "ETSY", "Doc", "doc.html",
                "Doc > A", "https://example.com/doc/#a", "2026-10-05", "text");

        List<PolicyRetriever.RetrievedChunk> result = controller(true, (marketplace, query, topK) -> {
            assertThat(marketplace).isEqualTo("ETSY");
            assertThat(query).isEqualTo("question");
            assertThat(topK).isEqualTo(3);
            return List.of(chunk);
        }).search("ETSY", "question", 3);

        assertThat(result).containsExactly(chunk);
    }
}
