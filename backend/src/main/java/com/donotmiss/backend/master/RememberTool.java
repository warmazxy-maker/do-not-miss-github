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
import java.time.LocalDate;
import java.util.Optional;

/**
 * Tandem writes a key fact into its own long-term memory. Internal write (F0 decision 3): it only
 * changes Tandem's own data, so it runs without a confirmation card; the student sees and can
 * delete every memory on the page (F1 decision 2).
 */
@Component
public class RememberTool implements AgentTool {
    public static final int MAX_MEMORIES = 50;

    private static final ToolDefinition DEFINITION;

    static {
        try {
            DEFINITION = new ToolDefinition(
                    "remember", 1, "记住",
                    "把学生亲口说的一条关键事实记进长期记忆，以后每次对话你都能看到。只记工具查不到、以后用得上的事："
                            + "PERSONAL 个人情况（年级、专业、日语水平）；CONSTRAINT 约束（周末要打工、不想参加线上活动）；"
                            + "PREFERENCE 偏好和反感；INTENTION 意向（想往后端方向发展）；COMMITMENT 约定（说好这周写完心得）。"
                            + "不要记活动、预约、日程、成绩、能力分、简历内容，这些用工具查。"
                            + "同一件事变了（比如 N2 变成 N1）就传 replaces_id 更新原来那条，不要新增。"
                            + "有时效的事（“这学期周末打工”）填 valid_until。一次只记一件事，用一句话写清楚。",
                    new ObjectMapper().readTree("""
                            {
                              "type": "object",
                              "properties": {
                                "category": {"type": "string", "enum": ["PERSONAL", "CONSTRAINT", "PREFERENCE", "INTENTION", "COMMITMENT"]},
                                "content": {"type": "string", "minLength": 2, "maxLength": 200, "description": "要记住的事实，一句话"},
                                "quote": {"type": "string", "maxLength": 200, "description": "学生的原话（摘录）"},
                                "valid_until": {"type": "string", "format": "date", "description": "到哪天为止有效，可不填"},
                                "replaces_id": {"type": "integer", "minimum": 1, "description": "要更新的那条记忆的 id，可不填"}
                              },
                              "required": ["category", "content"],
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
    private final ObjectMapper objectMapper;

    public RememberTool(TandemMemoryRepository memories, ObjectMapper objectMapper) {
        this.memories = memories;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return arguments.path("content").asText("");
    }

    @Override
    @Transactional
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        TandemMemoryEntity.Category category = TandemMemoryEntity.Category.valueOf(arguments.path("category").asText());
        String content = arguments.path("content").asText().trim();
        String quote = text(arguments, "quote");
        LocalDate validUntil = arguments.hasNonNull("valid_until") ? LocalDate.parse(arguments.path("valid_until").asText()) : null;

        TandemMemoryEntity memory;
        if (arguments.hasNonNull("replaces_id")) {
            Optional<TandemMemoryEntity> existing = memories.findByIdAndUserId(arguments.path("replaces_id").asLong(), caller.userId());
            if (existing.isEmpty()) {
                return ToolResult.error("没有 id 为 " + arguments.path("replaces_id").asLong() + " 的记忆，不能更新。要记新事实就不要传 replaces_id。",
                        "要更新的记忆不存在");
            }
            memory = existing.get();
            memory.update(category, content, quote, caller.runId(), validUntil);
        } else {
            if (memories.countByUserId(caller.userId()) >= MAX_MEMORIES) {
                return ToolResult.error("长期记忆已经有 " + MAX_MEMORIES + " 条，满了。先用 forget 删掉过时的，或用 replaces_id 更新已有的。",
                        "长期记忆满了（" + MAX_MEMORIES + " 条），这条没记上");
            }
            memory = TandemMemoryEntity.create(caller.userId(), category, content, quote, caller.runId(), validUntil);
        }
        memory = memories.save(memory);
        com.fasterxml.jackson.databind.node.ObjectNode data = objectMapper.createObjectNode();
        data.put("id", memory.getId());
        data.put("saved", true);
        return ToolResult.ok(data, "记住了：" + content);
    }

    private static String text(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }
}
