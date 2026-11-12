package com.thanhdatpb.listingguard.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads an error answer as a problem and picks the exception for it (ADR-023). The code comes from the {@code type} URN
 * ({@code urn:listing-guard:problem:<code>}) and falls back to the {@code code} member; members may be at the top level or under
 * {@code properties}.
 */
final class Problems {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "instance", "code", "requestId",
            "properties");

    private Problems() {
    }

    /** The problem's code, or {@code null} when the body is not a Listing Guard problem. Cheap enough for the retry decision. */
    static String code(ObjectMapper mapper, String body) {
        JsonNode root = object(mapper, body);
        return root == null ? null : code(root);
    }

    static ApiProblem read(ObjectMapper mapper, RawResponse response) {
        JsonNode root = object(mapper, response.body());
        String headerRequestId = clean(response.requestId());
        if (root == null) {
            return new ApiProblem(response.status(), null, null, null, null, null, headerRequestId, Map.of());
        }
        String code = code(root);
        String requestId = text(root, "requestId");
        Map<String, Object> properties = new LinkedHashMap<>();
        JsonNode nested = root.path("properties");
        if (nested.isObject()) {
            properties.putAll(members(mapper, nested));
        }
        properties.putAll(members(mapper, root));
        return new ApiProblem(
                response.status(),
                code == null ? null : text(root, "type"),
                code,
                code == null ? null : text(root, "title"),
                code == null ? null : text(root, "detail"),
                code == null ? null : text(root, "instance"),
                requestId != null && RawRequest.REQUEST_ID.matcher(requestId).matches() ? requestId : headerRequestId,
                code == null ? Map.of() : properties);
    }

    /** The exception for an error answer, by its code. */
    static ListingGuardApiException exception(ObjectMapper mapper, RawResponse response, Instant now) {
        ApiProblem problem = read(mapper, response);
        String code = problem.code();
        if (code == null) {
            return new ListingGuardApiException(problem);
        }
        return switch (code) {
            case "quota-exceeded" -> new QuotaExceededException(problem);
            case "plan-not-allowed" -> new PlanNotAllowedException(problem);
            case "rate-limited" -> new RateLimitedException(problem, retryAfter(response, now));
            case "invalid-request", "unprocessable-request" -> new ValidationException(problem);
            case "unknown-product-type" -> new UnknownProductTypeException(problem);
            default -> code.endsWith("-not-configured")
                    ? new NotConfiguredException(problem)
                    : new ListingGuardApiException(problem);
        };
    }

    private static Duration retryAfter(RawResponse response, Instant now) {
        return RetryPolicy.parseRetryAfter(response.retryAfter(), now);
    }

    private static String code(JsonNode root) {
        String type = text(root, "type");
        if (type != null && type.startsWith(ApiProblem.TYPE_PREFIX)) {
            String fromType = type.substring(ApiProblem.TYPE_PREFIX.length());
            if (CODE.matcher(fromType).matches()) {
                return fromType;
            }
        }
        String code = text(root, "code");
        if (code == null && root.path("properties").isObject()) {
            code = text(root.get("properties"), "code");
        }
        return code != null && CODE.matcher(code).matches() ? code : null;
    }

    private static Map<String, Object> members(ObjectMapper mapper, JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!STANDARD.contains(e.getKey()) && !e.getValue().isNull()) {
                out.put(e.getKey(), mapper.convertValue(e.getValue(), new TypeReference<Object>() {
                }));
            }
        }
        return out;
    }

    private static JsonNode object(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String clean(String requestId) {
        return requestId != null && RawRequest.REQUEST_ID.matcher(requestId).matches() ? requestId : null;
    }
}
