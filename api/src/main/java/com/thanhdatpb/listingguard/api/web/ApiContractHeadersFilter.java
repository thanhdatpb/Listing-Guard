package com.thanhdatpb.listingguard.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the contract headers of ADR-022 to every {@code /api/v1} response, before anything else can answer (so a {@code 401},
 * {@code 413} or {@code 429} written by a later filter carries them too): {@code X-Listing-Guard-Version}, and for an
 * operation this deployment deprecates, {@code Deprecation}, {@code Sunset} and {@code Link}. Bodies and statuses are untouched.
 */
public final class ApiContractHeadersFilter extends OncePerRequestFilter {

    public static final String VERSION_HEADER = "X-Listing-Guard-Version";
    public static final String DEPRECATION_HEADER = "Deprecation";
    public static final String SUNSET_HEADER = "Sunset";

    private final String version;
    private final ApiDeprecations deprecations;

    public ApiContractHeadersFilter(ServerVersion version, ApiDeprecations deprecations) {
        this.version = version.value();
        this.deprecations = deprecations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(VERSION_HEADER, version);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        deprecations.find(request.getMethod(), path).ifPresent(endpoint -> {
            response.setHeader(DEPRECATION_HEADER, endpoint.deprecationHeader());
            endpoint.sunsetHeader().ifPresent(sunset -> response.setHeader(SUNSET_HEADER, sunset));
            endpoint.linkHeader().ifPresent(link -> response.addHeader(HttpHeaders.LINK, link));
        });
        chain.doFilter(request, response);
    }
}
