package com.thanhdatpb.listingguard.api.llm;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.body;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "listing-guard.analyzer.enabled=true",
        "listing-guard.analyzer.llm.base-url=http://127.0.0.1:1/v1",
        "listing-guard.analyzer.llm.model=test-2026-10-01",
        "LISTING_GUARD_ANALYZER_LLM_API_KEY=synthetic-test-key"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PolicyLlmDeterministicIntegrationTest {
    @Autowired private MockMvc mvc;
    @MockitoSpyBean(name = "policyLlmProvider") private LlmProvider provider;

    @Test
    void checksDoNotUseThePolicyAdapterEvenWhenItIsEnabled() throws Exception {
        mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Synthetic mug sample")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisVersion").isNotEmpty())
                .andExpect(jsonPath("$.findings").isArray());
        verifyNoInteractions(provider);
    }
}
