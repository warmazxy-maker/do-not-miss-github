package com.donotmiss.backend.agentkit.actions;

import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool that changes the student's data (F2). It never runs when the model calls it: the call
 * becomes a confirmation card, and only the student's click runs it, from the arguments frozen on
 * the card, with no model in between (F2 decision 5).
 *
 * <p>Every such tool must also be able to roll its change back (F2 decision 7). A rollback only
 * reverts what this action did, and only while the thing it touched is exactly as the action left
 * it: if the student changed it afterwards, the rollback refuses instead of overwriting their
 * change.</p>
 *
 * <p>Each step calls the existing service, so the same rules apply as on the normal page.
 * Throw {@link com.donotmiss.backend.common.ApiException} with a plain reason when something cannot be done.</p>
 */
public interface ConfirmableTool extends AgentTool {
    /** Optional argument of every confirmable tool: the card this one replaces (F2 decision 4). */
    String REPLACES_ARGUMENT = "replaces_card";

    /**
     * Checks that it can be done now (first of two checks, F2 decision 5) and builds the card.
     * Must not change anything.
     *
     * @return the card and the payload that {@link #apply} will run with
     */
    Proposal propose(JsonNode arguments, ToolCaller caller) throws Exception;

    /** Runs the change. Called inside the confirmation transaction; the service checks again. */
    Applied apply(JsonNode payload, String userId) throws Exception;

    /** Rolls the change back. Called inside the rollback transaction. Returns a plain sentence. */
    String undo(JsonNode payload, JsonNode undoData, String userId) throws Exception;

    @Override
    default ToolResult execute(JsonNode arguments, ToolCaller caller) {
        throw new IllegalStateException("A confirmable tool runs through PendingActionService, not directly.");
    }

    record Proposal(ActionCard card, JsonNode payload) {
    }

    /**
     * @param message  one plain sentence for the card, e.g. "已加到日程表"
     * @param undoData what {@link #undo} needs: ids of what was created and how it looked right after
     */
    record Applied(String message, JsonNode undoData) {
    }
}
