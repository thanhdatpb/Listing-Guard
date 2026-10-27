package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Debug view of retrieval: what the retriever returns for a question. Read-only, but there is no
 * authentication yet, so it answers 404 unless {@code listing-guard.policies.search-endpoint.enabled=true}.
 */
@RestController
public class PolicySearchController {

    private final PolicyRetriever retriever;
    private final PolicyProperties properties;

    public PolicySearchController(PolicyRetriever retriever, PolicyProperties properties) {
        this.retriever = retriever;
        this.properties = properties;
    }

    @GetMapping("/policies/search")
    public List<PolicyRetriever.RetrievedChunk> search(
            @RequestParam String marketplace,
            @RequestParam("q") String query,
            @RequestParam(name = "k", defaultValue = "5") int k) {
        if (!properties.searchEndpoint().enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return retriever.search(marketplace, query, k);
    }
}
