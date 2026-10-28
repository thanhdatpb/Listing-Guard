package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.web.BodyLimitFilter;
import com.thanhdatpb.listingguard.api.web.RequestIdFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the request filters in a fixed order: request id, body size limit, API key, rate limit. Only for web
 * applications, so the one-shot jobs (ingest, evaluation) need no API keys. If the keys are missing or malformed
 * the application does not start.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({SecurityProperties.class, ApiProperties.class, RateLimitProperties.class})
public class ApiSecurityConfig {

    @Bean
    public ApiKeys apiKeys(SecurityProperties properties) {
        return ApiKeys.parse(properties.apiKeys());
    }

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties) {
        return new RateLimiter(properties);
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return register(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(ApiProperties properties, ObjectMapper mapper) {
        return register(new BodyLimitFilter(properties.maxBodyBytes(), mapper), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeys keys, ApiProperties properties, ObjectMapper mapper) {
        return register(new ApiKeyFilter(keys, properties.swaggerUiEnabled(), mapper), Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, RateLimitProperties properties,
                                                                  ObjectMapper mapper) {
        FilterRegistrationBean<RateLimitFilter> registration = register(new RateLimitFilter(limiter, mapper), Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setEnabled(properties.enabled());
        return registration;
    }

    private static <T extends Filter> FilterRegistrationBean<T> register(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
