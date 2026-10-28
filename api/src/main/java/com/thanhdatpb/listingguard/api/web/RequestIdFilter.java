package com.thanhdatpb.listingguard.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the client's {@code X-Request-Id} when it is a sane value, otherwise a new UUID. The
 * id goes into the response header, the log context (MDC) and every problem response, so one id ties a client's
 * report to the server's log lines.
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && VALID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        MDC.put(Problems.REQUEST_ID_MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Problems.REQUEST_ID_MDC_KEY);
        }
    }
}
