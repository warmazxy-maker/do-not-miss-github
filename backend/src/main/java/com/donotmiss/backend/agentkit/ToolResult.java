package com.donotmiss.backend.agentkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a tool call produced (F0 decision 4).
 *
 * @param data            for the model, already pruned of useless fields; null unless OK
 * @param messageForModel why it failed, or a note such as a truncation; null when there is nothing to say
 * @param summaryForUser  one plain sentence for the student, e.g. "查到 3 个预约"
 * @param truncation      set when the safety cap cut the result
 */
public record ToolResult(
        ToolResultStatus status,
        JsonNode data,
        String messageForModel,
        String summaryForUser,
        Truncation truncation
) {
    /** Only the first {@code returned} of {@code total} items came back. */
    public record Truncation(int returned, int total) {
    }

    public static ToolResult ok(JsonNode data, String summaryForUser) {
        return new ToolResult(ToolResultStatus.OK, data, null, summaryForUser, null);
    }

    public static ToolResult ok(JsonNode data, String summaryForUser, Truncation truncation) {
        String note = truncation == null ? null
                : "结果太多，只返回了前 " + truncation.returned() + " 条，一共有 " + truncation.total()
                + " 条。回答时必须如实告诉学生这一点，并建议缩小范围。";
        return new ToolResult(ToolResultStatus.OK, data, note, summaryForUser, truncation);
    }

    public static ToolResult error(String reason, String summaryForUser) {
        return new ToolResult(ToolResultStatus.ERROR, null, reason, summaryForUser, null);
    }

    public static ToolResult denied(String reason, String summaryForUser) {
        return new ToolResult(ToolResultStatus.DENIED, null, reason, summaryForUser, null);
    }
}
