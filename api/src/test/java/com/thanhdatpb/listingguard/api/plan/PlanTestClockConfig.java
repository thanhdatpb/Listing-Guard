package com.thanhdatpb.listingguard.api.plan;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the clock that decides which UTC month a result is counted in with one a test can set. Tests with plans on
 * import it together with {@code TestcontainersConfig} and {@code TrademarkReadyTestConfig} and the same annotations, so
 * they share one Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PlanTestClockConfig {

    @Bean
    @Primary
    public PlanTestSupport.MutableClock mutableClock() {
        return new PlanTestSupport.MutableClock(PlanTestSupport.NOVEMBER_15);
    }
}
