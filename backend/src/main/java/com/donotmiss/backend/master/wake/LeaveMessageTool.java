package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * F4: the inbox as a tool. Only the wake agent has it: Tandem leaves the student one message in
 * their conversation. Its own kind of write (a message from Tandem), so no confirmation card.
 */
@Component
public class LeaveMessageTool implements AgentTool {
    private static final ToolDefinition DEFINITION;

    static {
        try {
            DEFINITION = new ToolDefinition(
                    "leave_message", 1, "留言",
                    "在你和学生的对话里主动留一条消息，学生下次打开时会看到。每次叫醒最多留一条，把几件事合在一起说。"
                            + "不值得说就不要调用。",
                    new ObjectMapper().readTree("""
                            {
                              "type": "object",
                              "properties": {
                                "text": {"type": "string", "minLength": 1, "maxLength": 600, "description": "留言内容"}
                              },
                              "required": ["text"],
                              "additionalProperties": false
                            }
                            """),
                    new ToolRisk(ToolRisk.DataEffect.INTERNAL_WRITE, ToolRisk.ModelUsage.NONE),
                    Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final WakeDrafts drafts;

    public LeaveMessageTool(WakeDrafts drafts) {
        this.drafts = drafts;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        if (!drafts.put(caller.runId(), arguments.path("text").asText().trim())) {
            return ToolResult.denied("这次已经留过一条了，不能再留。", "这次已经留过言了");
        }
        return ToolResult.ok(null, "留了一条消息");
    }
}
