package com.thanhdatpb.listingguard.api.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The contract headers of ADR-022 on {@code /api/v1}: the server version and the configured deprecations. Only for web
 * applications; the one-shot jobs need none of it.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ApiDeprecationProperties.class)
public class ApiContractConfig {

    /** The {@code release} written by the {@code build-info} goal (Maven property {@code listing-guard.release}). */
    static final String RELEASE_PROPERTY = "release";

    @Bean
    public ServerVersion serverVersion(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        return ServerVersion.fromBuild(properties == null ? null : properties.get(RELEASE_PROPERTY));
    }

    @Bean
    public ApiDeprecations apiDeprecations(ApiDeprecationProperties properties) {
        return ApiDeprecations.of(properties.endpoints());
    }

    @Bean
    public FilterRegistrationBean<ApiContractHeadersFilter> apiContractHeadersFilter(ServerVersion version, ApiDeprecations deprecations) {
        FilterRegistrationBean<ApiContractHeadersFilter> registration =
                new FilterRegistrationBean<>(new ApiContractHeadersFilter(version, deprecations));
        // after the request id (HIGHEST_PRECEDENCE) and before the body limit, the API key and the rate limit, which may answer
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }

    @Bean
    ApiDeprecationStartupCheck apiDeprecationStartupCheck(ApiDeprecations deprecations,
                                                          @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        return new ApiDeprecationStartupCheck(deprecations, mappings);
    }
}
