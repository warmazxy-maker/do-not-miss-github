package com.donotmiss.backend.agentkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool an agent can call. Implementations are Spring beans; {@link ToolRegistry} collects them
 * at startup. A tool is a thin wrapper over an existing service and must not change how that
 * feature behaves for its normal users.
 *
 * <p>{@link #execute} only sees arguments that already passed the schema, the permission check and
 * the rate limit. It returns pruned data (useless fields dropped, very long text cut) but never
 * drops content for "relevance": bounded data comes back whole, unbounded data is bounded by
 * required range parameters (F0 decision 7). Throwing is fine: the executor turns it into an
 * honest error.</p>
 */
public interface AgentTool {
    ToolDefinition definition();

    ToolResult execute(JsonNode arguments, ToolCaller caller) throws Exception;

    /**
     * F1: a short plain detail for the progress list, e.g. "11/16～11/22" next to "查日程".
     * Arguments are already validated. Empty when there is nothing worth showing.
     */
    default String describe(JsonNode arguments) {
        return "";
    }
}
