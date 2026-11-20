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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * F3: change a goal: its words, type or date; add or drop milestones; or mark it completed,
 * abandoned or active again. Completing and abandoning are the student's call (F3 decision 4):
 * Tandem may only suggest, and the card is the student saying yes. The change runs only if the
 * goal still looks as it did when the card was made; the rollback restores the old snapshot.
 */
@Component
public class UpdateGoalTool implements ConfirmableTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "update_goal", 1, "改目标",
            "修改学生的一个目标（goal_id 来自系统给你的目标列表）：改文字、类型、日期，增加或去掉里程碑，"
                    + "或者把目标标为完成（COMPLETED）、放弃（ABANDONED）、重新开始（ACTIVE）。只填要改的。"
                    + "完成和放弃由学生决定：学生明确说了才出卡片；你觉得该完成或该放弃时，先在回答里问他，他同意了再出。"
                    + "去掉里程碑用里程碑编号。不会直接执行：生成一张卡片，学生确认后才改。",
            CreateGoalTool.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "goal_id": {"type": "integer", "minimum": 1},
                        "title": {"type": "string", "minLength": 1, "maxLength": 120},
                        "description": {"type": "string", "maxLength": 500, "description": "空字符串表示清空"},
                        "goal_type": {"type": "string", "enum": ["OUTCOME", "ABILITY", "HABIT"]},
                        "target_date": {"type": "string", "format": "date"},
                        "clear_target_date": {"type": "boolean", "description": "true 表示去掉目标日期"},
                        "status": {"type": "string", "enum": ["COMPLETED", "ABANDONED", "ACTIVE"]},
                        "add_milestones": {"type": "array", "items": %s, "description": "加在最后"},
                        "remove_milestone_ids": {"type": "array", "items": {"type": "integer", "minimum": 1}},
                        %s
                      },
                      "required": ["goal_id"],
                      "additionalProperties": false
                    }
                    """.formatted(CreateGoalTool.MILESTONE_ITEM, WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final TandemGoalService goals;
    private final TandemGoalMilestoneRepository milestones;
    private final ObjectMapper objectMapper;

    public UpdateGoalTool(TandemGoalService goals, TandemGoalMilestoneRepository milestones, ObjectMapper objectMapper) {
        this.goals = goals;
        this.milestones = milestones;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return "#" + arguments.path("goal_id").asText("");
    }

    @Override
    public Proposal propose(JsonNode arguments, ToolCaller caller) {
        String userId = caller.userId();
        TandemGoalEntity goal = goals.load(userId, arguments.path("goal_id").asLong());
        ObjectNode before = goals.snapshot(goal);
        List<TandemGoalMilestoneEntity> current = milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId());

        ObjectNode fields = objectMapper.createObjectNode();
        List<ActionCard.Field> lines = new ArrayList<>();
        if (arguments.has("title") && !arguments.path("title").asText().trim().equals(goal.getTitle())) {
            fields.put("title", arguments.path("title").asText().trim());
            lines.add(change("目标", goal.getTitle(), fields.path("title").asText()));
        }
        if (arguments.has("description")) {
            String text = WriteSupport.blankToNull(arguments.path("description").asText());
            if (!Objects.equals(text, goal.getDescription())) {
                fields.put("description", text);
                lines.add(change("说明", goal.getDescription(), text));
            }
        }
        if (arguments.has("goal_type") && !arguments.path("goal_type").asText().equals(goal.getGoalType().name())) {
            TandemGoalEntity.Type type = TandemGoalEntity.Type.valueOf(arguments.path("goal_type").asText());
            fields.put("goalType", type.name());
            lines.add(change("类型", goal.getGoalType().label(), type.label()));
        }
        String oldDate = goal.getTargetDate() == null ? null : goal.getTargetDate().toString();
        String newDate = arguments.path("clear_target_date").asBoolean(false) ? null
                : arguments.has("target_date") ? arguments.path("target_date").asText() : oldDate;
        if (!Objects.equals(oldDate, newDate)) {
            fields.put("targetDate", newDate);
            lines.add(change("目标日期", oldDate, newDate));
        }

        String status = null;
        if (arguments.has("status") && !arguments.path("status").asText().equals(goal.getStatus().name())) {
            status = arguments.path("status").asText();
            if ("ACTIVE".equals(status)) {
                goals.requireRoomForActive(userId, goal.getId());
            }
            lines.add(change("状态", goal.getStatus().label(), TandemGoalEntity.Status.valueOf(status).label()));
        }

        List<Long> removeIds = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        Set<Long> requested = new HashSet<>();
        arguments.path("remove_milestone_ids").forEach(id -> requested.add(id.asLong()));
        for (TandemGoalMilestoneEntity step : current) {
            if (requested.remove(step.getId())) {
                removeIds.add(step.getId());
                lines.add(new ActionCard.Field("去掉里程碑", step.getKind().label() + "：" + step.getTitle()));
            } else {
                kept.add(step.getKind().name() + ":" + step.getRefId());
            }
        }
        if (!requested.isEmpty()) {
            throw ApiException.badRequest("这个目标里没有里程碑 " + requested);
        }
        List<ObjectNode> added = arguments.has("add_milestones")
                ? goals.resolveMilestones(userId, arguments.path("add_milestones"), kept) : List.of();
        if (current.size() - removeIds.size() + added.size() > TandemGoalService.MAX_MILESTONES) {
            throw ApiException.badRequest("里程碑最多 " + TandemGoalService.MAX_MILESTONES + " 个");
        }
        int number = current.size() - removeIds.size();
        for (ObjectNode step : added) {
            ActionCard.Field field = TandemGoalService.milestoneField(++number, step);
            lines.add(new ActionCard.Field("新增" + field.label(), field.value()));
        }
        if (fields.isEmpty() && status == null && removeIds.isEmpty() && added.isEmpty()) {
            throw ApiException.badRequest("没有要改的内容，和现在一样");
        }

        String kind = status == null ? "改目标" : switch (status) {
            case "COMPLETED" -> "完成目标";
            case "ABANDONED" -> "放弃目标";
            default -> "重新开始目标";
        };
        List<String> effects = new ArrayList<>();
        if ("COMPLETED".equals(status) || "ABANDONED".equals(status)) {
            effects.add("目标会移到“过去的目标”，里程碑里的活动和挑战不受影响");
        }
        long newChallenges = added.stream().filter(step -> step.has("newChallenge")).count();
        if (newChallenges > 0) {
            effects.add("会同时新建 " + newChallenges + " 个挑战");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("goalId", goal.getId());
        payload.set("before", before);
        payload.set("fields", fields);
        payload.put("status", status);
        ArrayNode remove = payload.putArray("removeIds");
        removeIds.forEach(remove::add);
        ArrayNode add = payload.putArray("add");
        added.forEach(add::add);
        return new Proposal(new ActionCard(kind, goal.getTitle(), lines, effects, List.of(), null), payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        TandemGoalEntity goal = goals.load(userId, payload.path("goalId").asLong());
        if (!goals.matches(goal, payload.path("before"))) {
            throw ApiException.badRequest("卡片生成之后这个目标被改过了，为了不覆盖你的修改，没有执行。可以让 Tandem 按现在的样子重新准备");
        }
        goals.applyFields(goal, payload.path("fields"));
        if (payload.hasNonNull("status")) {
            TandemGoalEntity.Status status = TandemGoalEntity.Status.valueOf(payload.path("status").asText());
            if (status == TandemGoalEntity.Status.ACTIVE) {
                goals.requireRoomForActive(userId, goal.getId());
            }
            goal.setStatus(status);
        }
        List<TandemGoalMilestoneEntity> current = milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId());
        Set<Long> removeIds = new HashSet<>();
        payload.path("removeIds").forEach(id -> removeIds.add(id.asLong()));
        milestones.deleteAll(current.stream().filter(step -> removeIds.contains(step.getId())).toList());
        int last = current.stream().mapToInt(TandemGoalMilestoneEntity::getPosition).max().orElse(0);
        List<ObjectNode> created = goals.addMilestones(goal, payload.path("add"), last);
        milestones.flush();

        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("goalId", goal.getId());
        ArrayNode challenges = undo.putArray("challenges");
        created.forEach(challenges::add);
        undo.set("after", goals.snapshot(goal));
        String message = !payload.hasNonNull("status") ? "目标已修改" : switch (payload.path("status").asText()) {
            case "COMPLETED" -> "目标已标为完成";
            case "ABANDONED" -> "目标已标为放弃";
            default -> "目标已重新开始";
        };
        return new Applied(message, undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        TandemGoalEntity goal = goals.load(userId, undoData.path("goalId").asLong());
        if (!goals.matches(goal, undoData.path("after"))) {
            throw ApiException.badRequest("这个目标之后又被改过了，为了不覆盖你的修改，没有撤销");
        }
        goals.deleteCreatedChallenges(userId, undoData.path("challenges"));
        goals.restore(goal, payload.path("before"));
        return "已撤销：目标改回了原来的样子";
    }

    private static ActionCard.Field change(String label, String before, String after) {
        return new ActionCard.Field(label, (before == null ? "（空）" : before) + " → " + (after == null ? "（空）" : after));
    }
}
