package com.donotmiss.backend.agentkit;

/**
 * One call as the executor sees it. {@code argumentsJson} is the model's raw text; the executor
 * parses and validates it, not the tool.
 */
public record ToolCallRequest(String callId, String toolName, String argumentsJson, ToolCaller caller) {
}
