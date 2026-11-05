package com.thanhdatpb.listingguard.api.plan;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlanProperties.class)
public class PlanConfig {

    /** The clock that decides which UTC month a result is counted in. A test replaces it with a controllable one. */
    @Bean
    public Clock planClock() {
        return Clock.systemUTC();
    }
}
