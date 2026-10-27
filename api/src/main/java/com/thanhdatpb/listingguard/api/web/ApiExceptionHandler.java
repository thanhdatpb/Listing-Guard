package com.thanhdatpb.listingguard.api.web;

import com.thanhdatpb.listingguard.api.check.UnsupportedMarketplaceException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({UnsupportedMarketplaceException.class, IllegalArgumentException.class})
    public ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** The embedding backend (Ollama) is down or misconfigured; the message says what to check. */
    @ExceptionHandler(EmbeddingException.class)
    public ProblemDetail embeddingUnavailable(EmbeddingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
