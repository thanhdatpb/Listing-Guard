package com.thanhdatpb.listingguard.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.RawRequest;
import com.thanhdatpb.listingguard.client.RawResponse;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one tool call: checks the arguments, makes the one API call, and returns the API's answer as a tool result
 * (ADR-017). It never throws, decides nothing about the listing, retries nothing, and logs only the tool name, the
 * outcome, the request id and the time: never an argument (a listing is client data and a buyer's text is personal
 * data) and never the key.
 */
final class ToolRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);

    private final ApiGateway api;
    private final ToolResults results;
    private final ObjectMapper mapper;

    ToolRunner(ApiGateway api, ToolResults results, ObjectMapper mapper) {
        this.api = api;
        this.results = results;
        this.mapper = mapper;
    }

    CallToolResult run(ToolSpec tool, Map<String, Object> arguments) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String problem = ArgumentChecker.check(tool.inputSchema(), args);
            if (problem != null) {
                log.info("tool={} outcome=invalid-arguments requestId={} ms={}", tool.name(), requestId, millis(started));
                return results.invalidArguments(problem, requestId);
            }

            RawResponse response = api.call(toRequest(tool, args), requestId);
            log.info("tool={} status={} requestId={} ms={}", tool.name(), response.status(), requestId, millis(started));

            if (tool.endpoint() == Endpoint.TRADEMARK_CHECKS && response.status() == 503) {
                ToolResults.Problem problem503 = results.parseProblem(response, requestId);
                if (problem503.isTrademarkNotConfigured()) {
                    return results.trademarkNotConfigured(problem503);
                }
            }
            return results.fromResponse(response, requestId);
        } catch (ApiCallException e) {
            log.info("tool={} outcome={} requestId={} ms={}", tool.name(), e.code(), requestId, millis(started));
            return results.failure(e.code(), e.getMessage(), requestId);
        } catch (JsonProcessingException | RuntimeException e) {
            // Only the class: a message could carry part of an argument.
            log.error("tool={} outcome=internal-error cause={} requestId={}", tool.name(),
                    e.getClass().getSimpleName(), requestId);
            return results.failure(ApiCallException.INTERNAL, "The MCP server failed while handling the call.", requestId);
        }
    }

    /** The arguments of a check tool are the request body, passed on as the agent gave them (ADR-017). */
    private RawRequest toRequest(ToolSpec tool, Map<String, Object> args) throws JsonProcessingException {
        return switch (tool.endpoint()) {
            case RULESET_LIMITS -> RawRequest.rulesetLimits((String) args.get("marketplace"), (String) args.get("product_type"));
            case USAGE -> RawRequest.usage();
            case CAPABILITIES -> RawRequest.capabilities();
            default -> RawRequest.post(tool.endpoint().operation(), mapper.writeValueAsString(args));
        };
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
