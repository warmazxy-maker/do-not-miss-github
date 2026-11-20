package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.challenge.ChallengeDtos;
import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.challenge.ChallengeRepository;
import com.donotmiss.backend.challenge.ChallengeService;
import com.donotmiss.backend.challenge.ChallengeStatus;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.schedule.ScheduleItemRepository;
import com.donotmiss.backend.schedule.ScheduleItemStatus;
import com.donotmiss.backend.schedule.ScheduleItemType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F2: create a challenge. Rollback deletes it outright (a real rollback, not "cancelled"), but
 * only while it is still active, unchanged and not linked to a schedule item.
 */
@Component
public class CreateChallengeTool implements ConfirmableTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "create_challenge", 1, "建挑战",
            "为学生创建一个挑战。目标和描述要先和学生商量清楚，写得具体、可检验。"
                    + "不会直接执行：会生成一张卡片，学生点确认后才创建。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "title": {"type": "string", "minLength": 1, "maxLength": 120, "description": "挑战名称"},
                        "category": {"type": "string",
                                     "enum": ["技能挑战", "考试挑战", "身体挑战", "作品挑战", "生活挑战", "其他挑战"],
                                     "description": "挑战类型"},
                        "goal": {"type": "string", "minLength": 1, "maxLength": 500, "description": "要达到的目标"},
                        "description": {"type": "string", "minLength": 1, "maxLength": 2000, "description": "怎么做、什么算完成"},
                        %s
                      },
                      "required": ["title", "category", "goal", "description"],
                      "additionalProperties": false
                    }
                    """.formatted(WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final ChallengeService challengeService;
    private final ChallengeRepository challenges;
    private final ScheduleItemRepository scheduleItems;
    private final ObjectMapper objectMapper;

    public CreateChallengeTool(ChallengeService challengeService, ChallengeRepository challenges,
                               ScheduleItemRepository scheduleItems, ObjectMapper objectMapper) {
        this.challengeService = challengeService;
        this.challenges = challenges;
        this.scheduleItems = scheduleItems;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return arguments.path("title").asText("");
    }

    @Override
    public Proposal propose(JsonNode arguments, ToolCaller caller) {
        ObjectNode payload = objectMapper.createObjectNode();
        for (String field : List.of("title", "category", "goal", "description")) {
            String value = arguments.path(field).asText().trim();
            if (value.isEmpty()) {
                throw ApiException.badRequest("挑战的" + field + "不能为空");
            }
            payload.put(field, value);
        }
        ActionCard card = new ActionCard("建挑战", payload.path("title").asText(), List.of(
                new ActionCard.Field("类型", payload.path("category").asText()),
                new ActionCard.Field("目标", payload.path("goal").asText()),
                new ActionCard.Field("描述", WriteSupport.clip(payload.path("description").asText(), 300))),
                List.of("会出现在你的挑战列表里；完成时可以写心得，记进成长记录"), List.of(), "/student/challenges");
        return new Proposal(card, payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        ChallengeDtos.ChallengeResponse created = challengeService.create(userId, new ChallengeDtos.CreateChallengeRequest(
                payload.path("title").asText(), payload.path("category").asText(),
                payload.path("goal").asText(), payload.path("description").asText()));
        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("challengeId", created.id());
        undo.set("after", snapshot(created.title(), created.category(), created.goal(), created.description(), created.status()));
        return new Applied("挑战已创建", undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        ChallengeEntity challenge = challenges.findByIdAndUserId(undoData.path("challengeId").asLong(), userId)
                .orElseThrow(() -> ApiException.badRequest("这个挑战已经不在了"));
        if (challenge.getStatus() != ChallengeStatus.ACTIVE) {
            throw ApiException.badRequest("这个挑战已经完成或取消了，不能撤销");
        }
        ObjectNode now = snapshot(challenge.getTitle(), challenge.getCategory(), challenge.getGoal(),
                challenge.getDescription(), challenge.getStatus().name());
        if (!now.equals(undoData.path("after"))) {
            throw ApiException.badRequest("这个挑战创建之后被改过了，为了不覆盖你的修改，没有撤销");
        }
        boolean linked = scheduleItems.findByUserIdAndItemTypeAndSourceIdOrderByStartTimeAsc(
                        userId, ScheduleItemType.CHALLENGE, challenge.getId()).stream()
                .anyMatch(item -> item.getStatus() == ScheduleItemStatus.ACTIVE);
        if (linked) {
            throw ApiException.badRequest("你的日程里已经有关联这个挑战的安排，请先到日程页处理那一条");
        }
        challenges.delete(challenge);
        return "已撤销：这个挑战已删除";
    }

    private ObjectNode snapshot(String title, String category, String goal, String description, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", title);
        node.put("category", category);
        node.put("goal", goal);
        node.put("description", description);
        node.put("status", status);
        return node;
    }
}
