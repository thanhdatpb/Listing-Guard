package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import com.thanhdatpb.listingguard.api.image.ImageProperties;
import com.thanhdatpb.listingguard.api.web.BodyLimitFilter;
import com.thanhdatpb.listingguard.api.web.RequestIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Wires the request filters in a fixed order: request id, body size limit, API key, rate limit. Only for web
 * applications, so the one-shot jobs (ingest, evaluation, admin) need none of it. Clients and keys are read from
 * the database; when none exists the application still starts and logs a warning (ADR-007).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({SecurityProperties.class, ApiProperties.class, RateLimitProperties.class, ConsoleProperties.class})
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

    @Bean
    public ConsoleStartupSafetyCheck consoleStartupSafetyCheck(ConsoleProperties console, Environment environment) {
        return new ConsoleStartupSafetyCheck(console, environment.getProperty("spring.mail.host"), environment);
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
    public FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(ApiProperties properties, ImageProperties images,
                                                                   ObjectMapper mapper) {
        // the image endpoint takes a multipart body of its own, larger limit, but only while image checks are enabled: when
        // they are off a large upload gets the ordinary 413 and is never staged (ADR-008)
        BodyLimitFilter filter = images.enabled()
                ? new BodyLimitFilter(properties.maxBodyBytes(), mapper, "/api/v1/image-checks", images.maxRequestSize().toBytes())
                : new BodyLimitFilter(properties.maxBodyBytes(), mapper);
        return register(filter, Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ClientAuthenticator authenticator, ApiProperties properties,
                                                             ConsoleProperties console, ObjectMapper mapper) {
        return register(new ApiKeyFilter(authenticator, properties.swaggerUiEnabled(), console.enabled(), mapper),
                Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, RateLimitProperties properties,
                                                                  ObjectMapper mapper) {
        FilterRegistrationBean<RateLimitFilter> registration = register(new RateLimitFilter(limiter, mapper), Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setEnabled(properties.enabled());
        return registration;
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
    public SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/login", "/login/**", "/console", "/console/**", "/logout")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/login/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendRedirect("/login")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("LISTINGGUARDSESSION"))
                .headers(headers -> headers.referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers(ApiSecurityConfig::authenticatedByApiKey))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /**
     * CSRF forges a request that the browser authenticates by itself, with a cookie. This chain has no cookie and no
     * session: a request is authenticated only by the {@code X-API-Key} header, which {@link ApiKeyFilter} has already
     * checked (it runs before Spring Security and marks the request), and a page on another site cannot add that header.
     * So a request that carries a valid key is exempt, and every other request that reaches this chain (the health probe,
     * the development docs) keeps the default CSRF protection. The console chain above keeps it for its cookie session.
     */
    private static boolean authenticatedByApiKey(HttpServletRequest request) {
        return request.getAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) != null;
    }

    private static <T extends Filter> FilterRegistrationBean<T> register(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
