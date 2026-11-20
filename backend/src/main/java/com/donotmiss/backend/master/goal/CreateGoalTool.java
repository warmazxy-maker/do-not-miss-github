package com.donotmiss.backend.master.goal;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.agentkit.tools.WriteSupport;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * F3: set a goal with its milestones in one card (F3 decision 6). This is what the old planning
 * agent did, done better: Tandem searches events itself, picks and orders them, and turns
 * self-study steps into new challenges. Rollback deletes the goal and the challenges it created.
 */
@Component
public class CreateGoalTool implements ConfirmableTool {
    static final String MILESTONE_ITEM = """
            {
              "type": "object",
              "properties": {
                "event_id": {"type": "integer", "minimum": 1, "description": "作为这一步的活动 id（来自搜活动），必须还没结束"},
                "challenge_id": {"type": "integer", "minimum": 1, "description": "学生已有的挑战 id"},
                "new_challenge": {
                  "type": "object",
                  "description": "没有合适的活动时，用一个新挑战承载自学或练习",
                  "properties": {
                    "title": {"type": "string", "minLength": 1, "maxLength": 120},
                    "category": {"type": "string", "enum": ["技能挑战", "考试挑战", "身体挑战", "作品挑战", "生活挑战", "其他挑战"]},
                    "goal": {"type": "string", "minLength": 1, "maxLength": 500, "description": "这个挑战要达到什么"},
                    "description": {"type": "string", "minLength": 1, "maxLength": 2000, "description": "怎么做、什么算完成"}
                  },
                  "required": ["title", "category", "goal", "description"],
                  "additionalProperties": false
                },
                "note": {"type": "string", "maxLength": 200, "description": "为什么是这一步，一句话"}
              },
              "additionalProperties": false
            }
            """;

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "create_goal", 1, "建目标",
            "和学生一起定一个目标，连同 1～" + TandemGoalService.MAX_MILESTONES + " 个按顺序排好的步骤。"
                    + "每个步骤必须是平台上的一件事：一个活动（event_id，先用“搜活动”找），或学生已有的挑战，"
                    + "或一个新挑战（自学、练习这类没有对应活动的步骤）。进度按平台上真实发生的事算：活动签到了、挑战完成了才算这一步完成。"
                    + "进行中的目标最多 " + TandemGoalService.MAX_ACTIVE + " 个。不会直接执行：生成一张卡片，学生确认后才建。"
                    + "活动只是列为步骤，不会自动预约，需要预约时另外出预约卡片。",
            parse("""
                    {
                      "type": "object",
                      "properties": {
                        "title": {"type": "string", "minLength": 1, "maxLength": 120, "description": "目标，用学生自己的话"},
                        "description": {"type": "string", "maxLength": 500, "description": "怎样算达成、为什么想做"},
                        "goal_type": {"type": "string", "enum": ["OUTCOME", "ABILITY", "HABIT"],
                                      "description": "OUTCOME 结果型（考证、拿到实习）；ABILITY 能力型（口语能正常交流）；HABIT 习惯型（每周一次线下活动）"},
                        "target_date": {"type": "string", "format": "date", "description": "目标日期 YYYY-MM-DD，学生没说可以不填"},
                        "milestones": {"type": "array", "items": %s, "description": "按先后顺序"},
                        %s
                      },
                      "required": ["title", "goal_type", "milestones"],
                      "additionalProperties": false
                    }
                    """.formatted(MILESTONE_ITEM, WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final TandemGoalService goals;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public CreateGoalTool(TandemGoalService goals, ObjectMapper objectMapper,
                          @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.goals = goals;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    static JsonNode parse(String json) {
        try {
            return SCHEMA_MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
        goals.requireRoomForActive(caller.userId(), null);
        String title = arguments.path("title").asText().trim();
        LocalDate target = arguments.hasNonNull("target_date") ? LocalDate.parse(arguments.path("target_date").asText()) : null;
        if (target != null && target.isBefore(LocalDate.now(zone))) {
            throw ApiException.badRequest("目标日期已经过去了");
        }
        JsonNode items = arguments.path("milestones");
        if (items.isEmpty() || items.size() > TandemGoalService.MAX_MILESTONES) {
            throw ApiException.badRequest("步骤要有 1～" + TandemGoalService.MAX_MILESTONES + " 个");
        }
        List<ObjectNode> steps = goals.resolveMilestones(caller.userId(), items, Set.of());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", title);
        payload.put("description", WriteSupport.blankToNull(WriteSupport.text(arguments, "description")));
        payload.put("goalType", arguments.path("goal_type").asText());
        payload.put("targetDate", target == null ? null : target.toString());
        payload.put("runId", caller.runId());
        ArrayNode list = payload.putArray("milestones");
        steps.forEach(list::add);

        TandemGoalEntity.Type type = TandemGoalEntity.Type.valueOf(arguments.path("goal_type").asText());
        List<ActionCard.Field> fields = new ArrayList<>();
        fields.add(new ActionCard.Field("类型", type.label()));
        if (target != null) {
            fields.add(new ActionCard.Field("目标日期", target.toString()));
        }
        if (payload.hasNonNull("description")) {
            fields.add(new ActionCard.Field("说明", payload.path("description").asText()));
        }
        for (int i = 0; i < steps.size(); i++) {
            fields.add(TandemGoalService.milestoneField(i + 1, steps.get(i)));
        }
        long newChallenges = steps.stream().filter(step -> step.has("newChallenge")).count();
        List<String> effects = new ArrayList<>();
        effects.add("会出现在 Tandem 页右侧的目标里，进度按活动签到、挑战完成自动更新");
        if (newChallenges > 0) {
            effects.add("会同时新建 " + newChallenges + " 个挑战");
        }
        if (steps.stream().anyMatch(step -> "EVENT".equals(step.path("kind").asText()))) {
            effects.add("活动只是列为步骤，不会自动预约");
        }
        return new Proposal(new ActionCard("建目标", title, fields, effects, List.of(), null), payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        Long runId = payload.path("runId").isIntegralNumber() ? payload.path("runId").asLong() : null;
        TandemGoalService.Created created = goals.create(userId, runId, payload);
        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("goalId", created.goalId());
        ArrayNode challenges = undo.putArray("challenges");
        created.challenges().forEach(challenges::add);
        undo.set("after", goals.snapshot(goals.load(userId, created.goalId())));
        return new Applied(created.challenges().isEmpty() ? "目标已建好" : "目标已建好，新建了 " + created.challenges().size() + " 个挑战", undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        TandemGoalEntity goal = goals.load(userId, undoData.path("goalId").asLong());
        if (!goals.matches(goal, undoData.path("after"))) {
            throw ApiException.badRequest("这个目标建好之后被改过了（比如删了步骤、标了完成），为了不覆盖你的修改，没有撤销");
        }
        goals.deleteCreatedChallenges(userId, undoData.path("challenges"));
        goals.deleteWithMilestones(goal);
        return undoData.path("challenges").isEmpty() ? "已撤销：目标已删除" : "已撤销：目标和一起建的挑战都已删除";
    }
}
