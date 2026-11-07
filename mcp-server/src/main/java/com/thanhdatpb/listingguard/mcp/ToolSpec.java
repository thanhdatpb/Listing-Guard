package com.thanhdatpb.listingguard.mcp;

import java.util.Map;

/**
 * One MCP tool: its name, text for the agent, input schema and the single API call behind it (ADR-017).
 *
 * @param name        the fixed tool name
 * @param title       a short human title
 * @param description what the tool does, in English, written for the agent
 * @param endpoint    the one API call this tool makes
 * @param readOnly    {@code true} for the two readers; the check tools store a result and may cost a quota unit, so they
 *                    are not read-only (they are not destructive and an identical repeat is a free cache hit)
 * @param inputSchema a JSON Schema of {@code type: object}; the arguments are checked against it before any call
 */
record ToolSpec(
        String name,
        String title,
        String description,
        Endpoint endpoint,
        boolean readOnly,
        Map<String, Object> inputSchema) {
}
