package com.donotmiss.backend.master;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.UncheckedIOException;

/** Tandem removes one of its own long-term memories: the student asked, or it is no longer true. */
@Component
public class ForgetTool implements AgentTool {
    private static final ToolDefinition DEFINITION;

    static {
        try {
            DEFINITION = new ToolDefinition(
                    "forget", 1, "忘掉",
                    "从长期记忆里删掉一条。学生说“忘掉这个”“这个不对”，或者一条记忆已经不再成立时使用。"
                            + "memory_id 是长期记忆列表里每条前面的编号。",
                    new ObjectMapper().readTree("""
                            {
                              "type": "object",
                              "properties": {
                                "memory_id": {"type": "integer", "minimum": 1}
                              },
                              "required": ["memory_id"],
                              "additionalProperties": false
                            }
                            """),
                    new ToolRisk(ToolRisk.DataEffect.INTERNAL_WRITE, ToolRisk.ModelUsage.NONE),
                    Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private final TandemMemoryRepository memories;

    public ForgetTool(TandemMemoryRepository memories) {
        this.memories = memories;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    @Transactional
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        long id = arguments.path("memory_id").asLong();
        return memories.findByIdAndUserId(id, caller.userId())
                .map(memory -> {
                    memories.delete(memory);
                    return ToolResult.ok(null, "忘掉了：" + memory.getContent());
                })
                .orElseGet(() -> ToolResult.error("没有编号为 " + id + " 的记忆。", "要删的记忆不存在"));
    }
}
