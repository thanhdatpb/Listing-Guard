package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.web.BodyLimitFilter;
import com.thanhdatpb.listingguard.api.web.RequestIdFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Wires the request filters in a fixed order: request id, body size limit, API key, rate limit. Only for web
 * applications, so the one-shot jobs (ingest, evaluation, admin) need none of it. Clients and keys are read from
 * the database; when none exists the application still starts and logs a warning (ADR-007).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({SecurityProperties.class, ApiProperties.class, RateLimitProperties.class})
public class ApiSecurityConfig {

    @Bean
    public ClientAuthenticator clientAuthenticator(ApiKeyRepository keys, SecurityProperties properties) {
        return new DatabaseClientAuthenticator(keys, properties.authCacheTtl(), properties.lastUsedInterval());
    }

    /** Fails the start-up when development-only settings are combined with a profile that must not have them. */
    @Bean
    public StartupSafetyCheck startupSafetyCheck(SecurityProperties security, ApiProperties properties, Environment environment) {
        boolean devKeyConfigured = security.devApiKey() != null && !security.devApiKey().isBlank();
        return new StartupSafetyCheck(devKeyConfigured, properties.swaggerUiEnabled(), environment);
    }

    /** Registers the development key (when configured) and warns when no key can authenticate. */
    @Bean
    public ClientBootstrap clientBootstrap(ClientProvisioning provisioning, ApiKeyRepository keys, SecurityProperties properties,
                                           StartupSafetyCheck safetyCheck) {
        return new ClientBootstrap(provisioning, keys, properties.devApiKey());
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
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ClientAuthenticator authenticator, ApiProperties properties,
                                                             ObjectMapper mapper) {
        return register(new ApiKeyFilter(authenticator, properties.swaggerUiEnabled(), mapper), Ordered.HIGHEST_PRECEDENCE + 20);
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
