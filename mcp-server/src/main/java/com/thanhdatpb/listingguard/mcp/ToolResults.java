package com.thanhdatpb.listingguard.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns what the API answered (or why there was no answer) into an MCP tool result (ADR-017).
 *
 * <p>A {@code 2xx} answer is returned verbatim: the exact body as the text content and the parsed object, unchanged, as
 * the structured content. Anything the API refuses becomes a tool error ({@code isError: true}) with its stable
 * {@code code} and {@code requestId}; only a fixed list of members is copied, and long text is cut, so nothing the API
 * did not mean to say is forwarded. {@code 503 trademark-not-configured} on the trademark check is the one answer that is
 * not an error: the risk is unknown, never low.
 */
final class ToolResults {

    static final int MAX_DETAIL_CHARS = 500;
    private static final int MAX_FIELD_CHARS = 200;
    private static final int MAX_ITEMS = 50;
    private static final int MAX_ERROR_ITEMS = 20;
    private static final long MAX_RETRY_AFTER_SECONDS = 7L * 24 * 3600;

    static final String TRADEMARK_NOT_CONFIGURED = "trademark-not-configured";

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final ObjectMapper mapper;

    ToolResults(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A mapper that keeps the numbers of an answer exactly as written ({@code 1.0} stays {@code 1.0}). */
    static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        return mapper;
    }

    /** A problem document of the API, reduced to the members this server forwards. */
    record Problem(int status, String code, String requestId, Map<String, Object> members, Long retryAfterSeconds) {

        boolean isTrademarkNotConfigured() {
            return status == 503 && TRADEMARK_NOT_CONFIGURED.equals(code);
        }

        String detailOrTitle() {
            Object detail = members.get("detail");
            if (detail instanceof String s && !s.isBlank()) {
                return s;
            }
            return members.get("title") instanceof String t ? t : "";
        }
    }

    CallToolResult fromResponse(ApiResponse response, String sentRequestId) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            JsonNode root = parseObject(response.body());
            if (root == null) {
                return failure(ApiCallException.INVALID_RESPONSE,
                        "The API answered HTTP " + status + " but the body is not a JSON object.", sentRequestId);
            }
            return CallToolResult.builder()
                    .addTextContent(response.body())
                    .structuredContent(root)
                    .isError(false)
                    .build();
        }
        if (status >= 300 && status < 400) {
            return failure(ApiCallException.INVALID_RESPONSE,
                    "The API answered with a redirect (HTTP " + status + "). Redirects are not followed, so the key "
                            + "is never sent to another host. Check " + McpServerConfig.BASE_URL + ".",
                    sentRequestId);
        }
        return problemResult(parseProblem(response, sentRequestId));
    }

    Problem parseProblem(ApiResponse response, String sentRequestId) {
        JsonNode root = parseObject(response.body());
        JsonNode nested = root != null && root.path("properties").isObject() ? root.get("properties") : null;
        int status = response.status();

        String code = cleanCode(text(member(root, nested, "code")));
        if (code == null) {
            code = fallbackCode(status);
        }
        String requestId = firstRequestId(text(member(root, null, "requestId")), response.requestIdHeader(),
                sentRequestId);

        Map<String, Object> members = new LinkedHashMap<>();
        members.put("code", code);
        members.put("status", status);
        putText(members, "title", text(member(root, null, "title")), MAX_FIELD_CHARS);
        putText(members, "detail", text(member(root, null, "detail")), MAX_DETAIL_CHARS);
        members.put("requestId", requestId);
        List<Map<String, String>> errors = errors(member(root, nested, "errors"));
        if (!errors.isEmpty()) {
            members.put("errors", errors);
        }
        putText(members, "plan", text(member(root, nested, "plan")), MAX_FIELD_CHARS);
        putText(members, "pack", text(member(root, nested, "pack")), MAX_FIELD_CHARS);
        putNumber(members, "quota", member(root, nested, "quota"));
        putNumber(members, "used", member(root, nested, "used"));
        putText(members, "resetAt", text(member(root, nested, "resetAt")), MAX_FIELD_CHARS);
        List<String> known = textList(member(root, nested, "known"));
        if (!known.isEmpty()) {
            members.put("known", known);
        }
        return new Problem(status, code, requestId, members, retryAfter(response.retryAfterHeader()));
    }

    CallToolResult problemResult(Problem problem) {
        boolean retryable = retryable(problem);
        Map<String, Object> out = new LinkedHashMap<>(problem.members());
        out.put("retryable", retryable);
        if (problem.retryAfterSeconds() != null && "rate-limited".equals(problem.code())) {
            out.put("retryAfterSeconds", problem.retryAfterSeconds());
        }
        String hint = hint(problem);
        out.put("hint", hint);
        String message = "Listing Guard API error " + problem.code() + " (HTTP " + problem.status() + ")"
                + (problem.detailOrTitle().isEmpty() ? "" : ": " + problem.detailOrTitle())
                + " " + hint + " requestId=" + problem.requestId();
        return error(message, out);
    }

    /** A failure that is not an answer of the API: this server's own code, a generic message, no stack trace. */
    CallToolResult failure(String code, String message, String requestId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("title", title(code));
        out.put("detail", cut(message, MAX_DETAIL_CHARS));
        out.put("requestId", requestId);
        boolean retryable = ApiCallException.UNREACHABLE.equals(code) || ApiCallException.TIMEOUT.equals(code);
        out.put("retryable", retryable);
        String hint = ownHint(code);
        out.put("hint", hint);
        return error("Listing Guard MCP error " + code + ": " + cut(message, MAX_DETAIL_CHARS) + " " + hint
                + " requestId=" + requestId, out);
    }

    CallToolResult invalidArguments(String message, String requestId) {
        return failure("invalid-arguments", message, requestId);
    }

    /**
     * The one result this server builds itself: the deployment serves no trademark catalog, so no scan ran. It says so
     * ({@code derivedFrom}) and has no {@code findings} member, because an empty list would read as "nothing found".
     */
    CallToolResult trademarkNotConfigured(Problem problem) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("httpStatus", problem.status());
        derived.put("code", problem.code());
        derived.put("requestId", problem.requestId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NOT_CONFIGURED");
        out.put("riskLevel", "UNKNOWN");
        out.put("scanRan", false);
        out.put("derivedFrom", derived);
        if (!problem.detailOrTitle().isEmpty()) {
            out.put("detail", problem.detailOrTitle());
        }
        out.put("note", "This Listing Guard deployment serves no trademark catalog, so no trademark scan ran. The risk "
                + "is unknown, not low: do not report it as 'no trademark problem'. This object was built by the MCP "
                + "server from the API's 503 answer; it is not a result of the trademark scanner.");
        String text;
        try {
            text = mapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            text = "{\"status\":\"NOT_CONFIGURED\",\"riskLevel\":\"UNKNOWN\",\"scanRan\":false}";
        }
        return CallToolResult.builder().addTextContent(text).structuredContent(out).isError(false).build();
    }

    private static CallToolResult error(String message, Map<String, Object> structured) {
        return CallToolResult.builder().addTextContent(message).structuredContent(structured).isError(true).build();
    }

    // ---- reading the problem -------------------------------------------------------------------------------------

    private JsonNode parseObject(String body) {
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

    private static JsonNode member(JsonNode root, JsonNode nested, String name) {
        if (root != null) {
            JsonNode v = root.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        if (nested != null) {
            JsonNode v = nested.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static void putText(Map<String, Object> out, String name, String value, int max) {
        if (value != null && !value.isBlank()) {
            out.put(name, cut(value, max));
        }
    }

    private static void putNumber(Map<String, Object> out, String name, JsonNode node) {
        if (node != null && node.isNumber()) {
            out.put(name, node.numberValue());
        }
    }

    private static List<Map<String, String>> errors(JsonNode node) {
        List<Map<String, String>> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (list.size() >= MAX_ERROR_ITEMS) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            String field = text(item.get("field"));
            String message = text(item.get("message"));
            if (field != null) {
                entry.put("field", cut(field, MAX_FIELD_CHARS));
            }
            if (message != null) {
                entry.put("message", cut(message, MAX_FIELD_CHARS));
            }
            if (!entry.isEmpty()) {
                list.add(entry);
            }
        }
        return list;
    }

    private static List<String> textList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (list.size() >= MAX_ITEMS) {
                break;
            }
            if (item.isTextual()) {
                list.add(cut(item.asText(), MAX_FIELD_CHARS));
            }
        }
        return list;
    }

    private static String cleanCode(String code) {
        return code != null && CODE.matcher(code).matches() ? code : null;
    }

    private static String fallbackCode(int status) {
        return switch (status) {
            case 401 -> "unauthorized";
            case 429 -> "rate-limited";
            default -> status >= 500 ? "internal-error" : ApiCallException.INVALID_RESPONSE;
        };
    }

    private static String firstRequestId(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && REQUEST_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }

    private static Long retryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.strip());
            return seconds >= 0 && seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- what to tell the agent ----------------------------------------------------------------------------------

    private static boolean retryable(Problem p) {
        return switch (p.code()) {
            case "unauthorized", "plan-not-allowed", "quota-exceeded", TRADEMARK_NOT_CONFIGURED -> false;
            case "rate-limited" -> true;
            default -> p.status() == 429 ? p.retryAfterSeconds() != null : p.status() >= 500;
        };
    }

    private static String hint(Problem p) {
        return switch (p.code()) {
            case "unauthorized" -> "The API rejected the key in " + McpServerConfig.API_KEY
                    + " (missing, wrong or revoked). Ask the operator for a valid key; do not retry.";
            case "plan-not-allowed" -> "The client's plan does not include this check. Retrying does not help; ask the "
                    + "operator for a plan that includes it.";
            case "rate-limited" -> "Too many requests for this client. Wait retryAfterSeconds (when given), then retry.";
            case "quota-exceeded" -> "The monthly quota of the client's plan is used up. Do not retry before resetAt "
                    + "or a plan change; results already stored are still answered.";
            case TRADEMARK_NOT_CONFIGURED -> "The deployment serves no trademark catalog. Retrying does not help.";
            default -> p.status() >= 500
                    ? "The API had a problem. Retry later and quote the requestId when you report it."
                    : "The API refused the request. Fix the input; do not retry it unchanged.";
        };
    }

    private static String ownHint(String code) {
        return switch (code) {
            case ApiCallException.UNREACHABLE -> "Check " + McpServerConfig.BASE_URL
                    + " and that the API is running, then retry later.";
            case ApiCallException.TIMEOUT -> "The API may be busy or down. Retry later.";
            case ApiCallException.INVALID_RESPONSE -> "The server at " + McpServerConfig.BASE_URL
                    + " did not answer like the Listing Guard API. Check the URL; do not retry unchanged.";
            case "invalid-arguments" -> "Fix the arguments to match the tool's input schema; do not retry them unchanged.";
            default -> "Report this with the requestId.";
        };
    }

    private static String title(String code) {
        return switch (code) {
            case ApiCallException.UNREACHABLE -> "The API could not be reached";
            case ApiCallException.TIMEOUT -> "The API did not answer in time";
            case ApiCallException.INVALID_RESPONSE -> "The API answer could not be used";
            case "invalid-arguments" -> "Invalid arguments";
            default -> "The MCP server failed";
        };
    }

    /** Cuts at {@code max} characters without leaving half of a surrogate pair, and drops control characters. */
    static String cut(String value, int max) {
        String clean = value.replaceAll("\\p{Cntrl}", " ");
        if (clean.length() <= max) {
            return clean;
        }
        int end = max;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end) + "...";
    }
}
