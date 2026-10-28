package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service authentication with the {@code X-API-Key} header. Everything needs a key except
 * {@code /health}. With the development flag on, Swagger UI and the OpenAPI document are open as well.
 * A request that passes carries the key's name in the request attribute {@link #KEY_NAME_ATTRIBUTE}.
 */
public final class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String KEY_NAME_ATTRIBUTE = ApiKeyFilter.class.getName() + ".keyName";
    public static final String KEY_NAME_MDC_KEY = "apiKey";

    private final ApiKeys keys;
    private final boolean docsPublic;
    private final ObjectMapper mapper;

    public ApiKeyFilter(ApiKeys keys, boolean docsPublic, ObjectMapper mapper) {
        this.keys = keys;
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
        Optional<String> name = keys.authenticate(presented);
        if (name.isEmpty()) {
            reject(request, response, "The " + HEADER + " header does not contain a valid API key.");
            return;
        }
        request.setAttribute(KEY_NAME_ATTRIBUTE, name.get());
        MDC.put(KEY_NAME_MDC_KEY, name.get());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(KEY_NAME_MDC_KEY);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        response.setHeader("WWW-Authenticate", "ApiKey realm=\"listing-guard\"");
        ProblemResponses.write(request, response, mapper, HttpStatus.UNAUTHORIZED, "unauthorized", detail);
    }
}
