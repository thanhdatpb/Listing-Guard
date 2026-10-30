package com.thanhdatpb.listingguard.api.web;

import com.thanhdatpb.listingguard.api.check.CheckNotFoundException;
import com.thanhdatpb.listingguard.api.check.MarketplaceNotYetSupportedException;
import com.thanhdatpb.listingguard.api.check.UnknownMarketplaceException;
import com.thanhdatpb.listingguard.api.policy.CorpusModelMismatchException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckNotFoundException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingException;
import com.thanhdatpb.listingguard.rag.embedding.EmbeddingNotConfiguredException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into an RFC 7807 problem with a stable {@code code} (see {@link Problems}). Spring's own
 * exceptions (bad JSON, wrong method, wrong media type, unknown route) are handled by the base class and get the
 * same shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnknownMarketplaceException.class)
    public ProblemDetail unknownMarketplace(UnknownMarketplaceException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "unknown-marketplace", e.getMessage());
    }

    @ExceptionHandler(MarketplaceNotYetSupportedException.class)
    public ProblemDetail marketplaceNotYetSupported(MarketplaceNotYetSupportedException e) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "marketplace-not-yet-supported", e.getMessage());
    }

    @ExceptionHandler(CheckNotFoundException.class)
    public ProblemDetail checkNotFound(CheckNotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "check-not-found", e.getMessage());
    }

    /** A trademark check of another client, an unknown one and an expired one all answer alike (ADR-007, ADR-009). */
    @ExceptionHandler(TrademarkCheckNotFoundException.class)
    public ProblemDetail trademarkCheckNotFound(TrademarkCheckNotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, "trademark-check-not-found", e.getMessage());
    }

    /** No trademark catalog is served (not configured, or refused); only trademark checks are affected, not /checks (ADR-009). */
    @ExceptionHandler(TrademarkUnavailableException.class)
    public ProblemDetail trademarkNotConfigured(TrademarkUnavailableException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "trademark-not-configured", e.getMessage());
    }

    /** Input that a lower layer rejected, such as a listing that is not a JSON object. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidInput(IllegalArgumentException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
    }

    /** No embedding provider is selected (the default); only what needs embeddings is affected, rule checks are not (ADR-012). */
    @ExceptionHandler(EmbeddingNotConfiguredException.class)
    public ProblemDetail embeddingNotConfigured(EmbeddingNotConfiguredException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "embedding-not-configured", e.getMessage());
    }

    /** The embedding backend (the embedding API or Ollama) is down or misconfigured; the message says what to check. */
    @ExceptionHandler(EmbeddingException.class)
    public ProblemDetail embeddingUnavailable(EmbeddingException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "embedding-unavailable", e.getMessage());
    }

    /** The stored corpus was embedded by another model; searching would return wrong results, so it is refused (ADR-012). */
    @ExceptionHandler(CorpusModelMismatchException.class)
    public ProblemDetail corpusModelMismatch(CorpusModelMismatchException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "corpus-model-mismatch", e.getMessage());
    }

    /** Anything unexpected: logged with its request id, answered without internal details. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred. Quote the requestId when reporting it.");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException) {
                ProblemDetail tooLarge = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", cause.getMessage() + ".");
                return handleExceptionInternal(ex, tooLarge, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
            }
        }
        ProblemDetail problem = Problems.of(status, "invalid-request", "The request body is missing or is not valid JSON.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(status, "invalid-request", "The request is not valid; see errors.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Gives every problem built by Spring itself the same {@code type}, {@code code} and {@code requestId}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (ex instanceof NoResourceFoundException && request instanceof ServletWebRequest web) {
                problem.setDetail("No endpoint matches " + web.getRequest().getRequestURI());
            }
            Problems.decorate(problem, Problems.codeFor(status));
        }
        return response;
    }
}
