package com.thanhdatpb.listingguard.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the request rate per client, whatever key it used. Runs after {@link ApiKeyFilter}; a request without a
 * client (such as {@code /health}) is not limited. Too many requests get 429 with {@code Retry-After}.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object client = request.getAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE);
        if (client instanceof AuthenticatedClient authenticated) {
            long waitNanos = limiter.acquire(authenticated.id().toString());
            if (waitNanos > 0) {
                long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos) + 1);
                response.setHeader("Retry-After", String.valueOf(seconds));
                ProblemResponses.write(request, response, mapper, HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
                        "Too many requests for this client. Retry after " + seconds + " second(s).");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
