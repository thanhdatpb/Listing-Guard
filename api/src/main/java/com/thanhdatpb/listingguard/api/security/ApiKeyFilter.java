package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service authentication with the {@code X-API-Key} header. Everything needs a key except
 * {@code /health}. With the development flag on, Swagger UI and the OpenAPI document are open as well.
 * A request that passes carries the calling client in the request attribute {@link #CLIENT_ATTRIBUTE}. A key that is
 * unknown, revoked or belongs to an inactive client is rejected with the same 401 answer.
 */
public final class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    /** A compile-time constant, so controllers can name it in {@code @RequestAttribute}. */
    public static final String CLIENT_ATTRIBUTE = "com.thanhdatpb.listingguard.api.security.ApiKeyFilter.client";
    public static final String CLIENT_MDC_KEY = "client";
    public static final String KEY_ID_MDC_KEY = "keyId";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final ClientAuthenticator authenticator;
    private final boolean docsPublic;
    private final ObjectMapper mapper;

    public ApiKeyFilter(ClientAuthenticator authenticator, boolean docsPublic, ObjectMapper mapper) {
        this.authenticator = authenticator;
        this.docsPublic = docsPublic;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/health")) {
            return true;
        }
        return docsPublic && (path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/") || path.startsWith("/v3/api-docs"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            reject(request, response, "The " + HEADER + " header is missing.");
            return;
        }
        Optional<AuthenticatedClient> client;
        try {
            client = authenticator.authenticate(presented);
        } catch (RuntimeException e) {
            log.error("Could not check the API key", e);
            ProblemResponses.write(request, response, mapper, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                    "The API key could not be checked right now. Try again shortly.");
            return;
        }
        if (client.isEmpty()) {
            reject(request, response, "The " + HEADER + " header does not contain a valid API key.");
            return;
        }
        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        MDC.put(CLIENT_MDC_KEY, client.get().name());
        MDC.put(KEY_ID_MDC_KEY, client.get().keyId());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLIENT_MDC_KEY);
            MDC.remove(KEY_ID_MDC_KEY);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        response.setHeader("WWW-Authenticate", "ApiKey realm=\"listing-guard\"");
        ProblemResponses.write(request, response, mapper, HttpStatus.UNAUTHORIZED, "unauthorized", detail);
    }
}
