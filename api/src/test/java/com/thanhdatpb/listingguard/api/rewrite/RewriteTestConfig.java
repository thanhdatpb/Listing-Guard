package com.thanhdatpb.listingguard.api.rewrite;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Puts a {@link StubLlm} in the context as the {@code LlmProvider} (ADR-036 supplies the real one). */
@TestConfiguration(proxyBeanMethods = false)
public class RewriteTestConfig {

    @Bean
    StubLlm stubLlm() {
        return new StubLlm();
    }
}
