package com.donotmiss.backend.agentkit;

/**
 * Who a tool call is for. Filled by the system, never by the model: the user id is not a tool
 * parameter, so a model cannot ask for someone else's data (F0 decision 4).
 *
 * @param userId    whose data the tool reads or changes
 * @param agentName which agent made the call
 * @param runId     the agent run it belongs to; may be null in tests
 * @param readOnly  only read tools may run (the ops playground acts on someone else's data)
 */
public record ToolCaller(String userId, String agentName, Long runId, boolean readOnly) {
    public ToolCaller(String userId, String agentName, Long runId) {
        this(userId, agentName, runId, false);
    }
}
