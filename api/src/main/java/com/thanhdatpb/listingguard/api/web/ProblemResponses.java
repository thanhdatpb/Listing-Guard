package com.thanhdatpb.listingguard.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Writes a problem response from a servlet filter, where Spring MVC's exception handling does not reach. */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
                             HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = Problems.of(status, code, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
