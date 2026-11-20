package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.agentkit.tools.WriteSupport;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.ai.ToolChat;
import com.donotmiss.backend.master.TandemMessageEntity;
import com.donotmiss.backend.master.TandemMessageRepository;
import com.donotmiss.backend.master.TandemService;
import com.donotmiss.backend.master.goal.TandemGoalEntity;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneEntity;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneRepository;
import com.donotmiss.backend.master.goal.TandemGoalRepository;
import com.donotmiss.backend.master.goal.TandemGoalService;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.donotmiss.backend.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * F4: turns due wake triggers into at most one message per student per pass.
 *
 * <ol>
 *   <li>Claims the student's due triggers, plus the ones due within the merge window, so things
 *       that happened close together become one message (decision 4).</li>
 *   <li>Drops what the student switched off (decision 5) and what is no longer true (a cancelled
 *       reservation, a goal already reviewed).</li>
 *   <li>Past the daily limit, moves everything to tomorrow morning; it is merged into that day's
 *       first message, never dropped (decision 4).</li>
 *   <li>Reminders only: fixed text, no model (decision 3). Anything else: a wake run of Tandem,
 *       which may decide to say nothing. A model failure is retried once after 5 minutes, then
 *       given up (decision 7).</li>
 * </ol>
 *
 * <p>The message goes into the conversation with a hidden note for later turns, so when the
 * student answers a reminder Tandem knows exactly what it was about (decisions 1 and 3).</p>
 */
@Component
public class WakeProcessor {
    private static final Logger log = LoggerFactory.getLogger(WakeProcessor.class);
    static final int MAX_PER_DAY = 3;
    static final int MAX_ATTEMPTS = 2;
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private static final Map<String, String> RANK_LABELS = Map.of(
            "UNRATED", "待评估", "FOUNDATION", "基础", "DEVELOPING", "成长中",
            "PROFICIENT", "熟练", "ADVANCED", "进阶", "EXPERT", "专家");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final WakeTriggerRepository triggers;
    private final WakeSettingsRepository settings;
    private final TandemMessageRepository messages;
    private final TandemService tandemService;
    private final TandemGoalRepository goals;
    private final TandemGoalMilestoneRepository steps;
    private final TandemGoalService goalService;
    private final ReservationRepository reservations;
    private final AgentRegistry agents;
    private final AgentLoop agentLoop;
    private final WakeDrafts drafts;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;
    private final boolean enabled;

    public WakeProcessor(WakeTriggerRepository triggers, WakeSettingsRepository settings, TandemMessageRepository messages,
                         TandemService tandemService, TandemGoalRepository goals, TandemGoalMilestoneRepository steps,
                         TandemGoalService goalService, ReservationRepository reservations, AgentRegistry agents,
                         AgentLoop agentLoop, WakeDrafts drafts, ObjectMapper objectMapper,
                         @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone,
                         @Value("${app.tandem.wake.enabled:true}") boolean enabled) {
        this.triggers = triggers;
        this.settings = settings;
        this.messages = messages;
        this.tandemService = tandemService;
        this.goals = goals;
        this.steps = steps;
        this.goalService = goalService;
        this.reservations = reservations;
        this.agents = agents;
        this.agentLoop = agentLoop;
        this.drafts = drafts;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.tandem.wake.initial-delay-ms:30000}",
            fixedDelayString = "${app.tandem.wake.process-delay-ms:60000}")
    public void processDue() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        for (String userId : triggers.usersWithDue(WakeTriggerStatus.PENDING, now)) {
            try {
                processUser(userId, now);
            } catch (RuntimeException ex) {
                log.warn("Tandem wake for a student failed; its triggers stay as they are.", ex);
            }
        }
    }

    /** What happened to one pass for one student; for tests and logs. */
    enum Outcome { NOTHING, SKIPPED, DEFERRED, RETRY, SENT }

    Outcome processUser(String userId, Instant now) {
        List<Long> claimed = triggers.findByUserIdAndStatusAndProcessAfterLessThanEqualOrderByIdAsc(
                        userId, WakeTriggerStatus.PENDING, now.plus(WakeScanner.MERGE_WINDOW)).stream()
                .map(WakeTriggerEntity::getId)
                .filter(id -> triggers.transition(id, WakeTriggerStatus.PENDING, WakeTriggerStatus.PROCESSING) == 1)
                .toList();
        if (claimed.isEmpty()) {
            return Outcome.NOTHING;
        }
        List<WakeTriggerEntity> batch = triggers.findAllById(claimed);
        WakeSettingsEntity switches = settings.findById(userId).orElseGet(() -> WakeSettingsEntity.defaults(userId));

        List<WakeTriggerEntity> keep = new ArrayList<>();
        for (WakeTriggerEntity trigger : batch) {
            if (!switches.allows(trigger.getKind())) {
                trigger.finish(WakeTriggerStatus.SKIPPED, "学生关掉了这类留言", null, null, now);
            } else if (!stillTrue(trigger)) {
                trigger.finish(WakeTriggerStatus.SKIPPED, "情况已经变了，不用再说", null, null, now);
            } else {
                keep.add(trigger);
            }
        }
        if (keep.isEmpty()) {
            triggers.saveAll(batch);
            return Outcome.SKIPPED;
        }

        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        if (messages.countByUserIdAndProactiveTrueAndCreatedAtGreaterThanEqual(userId, todayStart) >= MAX_PER_DAY) {
            Instant tomorrowMorning = LocalDate.now(zone).plusDays(1).atTime(WakeScanner.DAY_STARTS_AT, 0).atZone(zone).toInstant();
            keep.forEach(trigger -> trigger.retryAt(tomorrowMorning, "今天已经留了 " + MAX_PER_DAY + " 条，挪到明天早上一起说"));
            triggers.saveAll(batch);
            return Outcome.DEFERRED;
        }

        List<WakeTriggerEntity> reminders = keep.stream().filter(trigger -> trigger.getKind().template()).toList();
        Outcome outcome;
        if (reminders.size() == keep.size()) {
            send(userId, reminderText(reminders), reminderNote(reminders), null, reminders, now);
            outcome = Outcome.SENT;
        } else {
            outcome = wake(userId, keep, reminders, now);
        }
        triggers.saveAll(batch);
        return outcome;
    }

    /** A wake run of Tandem; it may leave one message or none. */
    private Outcome wake(String userId, List<WakeTriggerEntity> keep, List<WakeTriggerEntity> reminders, Instant now) {
        List<String> facts = keep.stream().map(this::fact).toList();
        AgentLoop.Reply reply = null;
        String draft = null;
        try {
            AgentSpec spec = agents.find(WakeAgentSpec.NAME).orElseThrow();
            List<ToolChat.Message> history = tandemService.history(userId, null);
            String event = "【平台事件，不是学生发的消息】\n" + numbered(facts) + "\n要不要给学生留言，由你决定。";
            reply = agentLoop.run(spec, userId, userId, AgentRunType.MASTER_WAKE, history, event, AgentLoop.Listener.NONE);
            draft = drafts.take(reply.runId());
        } catch (RuntimeException ex) {
            log.warn("Tandem wake run failed.", ex);
        }
        Long runId = reply == null ? null : reply.runId();
        boolean failed = reply == null || ("MODEL_UNAVAILABLE".equals(reply.stopReason()) && draft == null);
        if (failed) {
            for (WakeTriggerEntity trigger : keep) {
                trigger.countAttempt();
                if (trigger.getAttempts() >= MAX_ATTEMPTS) {
                    trigger.finish(WakeTriggerStatus.FAILED, "模型连续失败，放弃这次留言", null, runId, now);
                } else {
                    trigger.retryAt(now.plus(RETRY_AFTER), "模型失败，5 分钟后重试");
                }
            }
            return Outcome.RETRY;
        }
        if (draft == null || draft.isBlank()) {
            // Tandem judged it not worth a message; a reminder still goes out, it is plain information.
            List<WakeTriggerEntity> quiet = keep.stream().filter(trigger -> !trigger.getKind().template()).toList();
            quiet.forEach(trigger -> trigger.finish(WakeTriggerStatus.SKIPPED, "Tandem 觉得不必说", null, runId, now));
            if (!reminders.isEmpty()) {
                send(userId, reminderText(reminders), reminderNote(reminders), null, reminders, now);
                return Outcome.SENT;
            }
            return Outcome.SKIPPED;
        }
        String note = "（系统记录：下面这条是你被平台事件叫醒后主动给学生的留言。起因：" + String.join("；", facts)
                + "。学生接下来的话可能是在回应它。）";
        send(userId, draft.trim(), note, runId, keep, now);
        return Outcome.SENT;
    }

    private void send(String userId, String text, String note, Long runId, List<WakeTriggerEntity> answered, Instant now) {
        TandemMessageEntity message = messages.save(TandemMessageEntity.proactive(userId, text, note, runId));
        answered.forEach(trigger -> trigger.finish(WakeTriggerStatus.DONE, null, message.getId(), runId, now));
        tandemService.compressLater(userId);
    }

    /** A trigger can go stale between being found and being said. */
    private boolean stillTrue(WakeTriggerEntity trigger) {
        JsonNode payload = payload(trigger);
        return switch (trigger.getKind()) {
            case EVENT_TOMORROW -> reservations.findById(payload.path("reservationId").asLong())
                    .map(reservation -> reservation.getStatus() == ReservationStatus.RESERVED).orElse(false);
            case GOAL_STEP -> activeGoal(trigger.getUserId(), payload.path("goalId").asLong()) != null;
            case GOAL_REVIEW -> {
                TandemGoalEntity goal = activeGoal(trigger.getUserId(), payload.path("goalId").asLong());
                Instant since = goal == null ? null
                        : goal.getLastReviewedAt() == null ? goal.getCreatedAt() : goal.getLastReviewedAt();
                yield since != null && since.toString().equals(payload.path("since").asText());
            }
            default -> true;
        };
    }

    // ---- wording ----

    /** One line for the model (and the hidden note), e.g. "活动签到成功：「日语会话角」（活动 #65）". */
    String fact(WakeTriggerEntity trigger) {
        JsonNode p = payload(trigger);
        return switch (trigger.getKind()) {
            case CHECK_IN -> "活动签到成功：「" + p.path("title").asText() + "」（活动 #" + p.path("eventId").asLong() + "）";
            case GOAL_STEP -> {
                TandemGoalEntity goal = activeGoal(trigger.getUserId(), p.path("goalId").asLong());
                String progress = "";
                if (goal != null) {
                    TandemGoalService.GoalView view = goalService.view(goal);
                    progress = "，目标现在进度 " + view.done() + "/" + view.total()
                            + (view.done() == view.total() ? "（所有步骤都完成了）" : "");
                }
                String kind = "EVENT".equals(p.path("kind").asText()) ? "活动" : "挑战";
                yield "目标「" + p.path("goalTitle").asText() + "」（目标 #" + p.path("goalId").asLong() + "）的一步完成了："
                        + kind + "「" + p.path("stepTitle").asText() + "」" + progress;
            }
            case RANK_UP -> "能力晋级：「" + p.path("bucketName").asText() + "」从「"
                    + RANK_LABELS.getOrDefault(p.path("fromRank").asText(), p.path("fromRank").asText()) + "」升到「"
                    + RANK_LABELS.getOrDefault(p.path("toRank").asText(), p.path("toRank").asText()) + "」";
            case EVENT_TOMORROW -> "明天有预约的活动：「" + p.path("title").asText() + "」"
                    + WriteSupport.timeRange(time(p, "start"), time(p, "end"))
                    + (p.hasNonNull("location") ? "，地点 " + p.path("location").asText() : "")
                    + "（活动 #" + p.path("eventId").asLong() + "）" + goalOf(trigger.getUserId(), p.path("eventId").asLong());
            case GOAL_REVIEW -> "目标「" + p.path("goalTitle").asText() + "」（目标 #" + p.path("goalId").asLong()
                    + "）已经 " + Duration.between(Instant.parse(p.path("since").asText()), Instant.now()).toDays()
                    + " 天没有回顾了";
        };
    }

    /** Fixed text for reminders (decision 3): no model, nothing invented. */
    String reminderText(List<WakeTriggerEntity> reminders) {
        if (reminders.size() == 1) {
            JsonNode p = payload(reminders.get(0));
            LocalDateTime start = time(p, "start");
            return "提醒一下：你预约的「" + p.path("title").asText() + "」明天 " + (start == null ? "" : start.format(TIME))
                    + " 开始" + (p.hasNonNull("location") ? "，地点是 " + p.path("location").asText() : "") + "。"
                    + goalOf(reminders.get(0).getUserId(), p.path("eventId").asLong())
                    + "\n需要我帮忙的话，直接回复我。";
        }
        String lines = reminders.stream().map(trigger -> {
            JsonNode p = payload(trigger);
            LocalDateTime start = time(p, "start");
            return "· 「" + p.path("title").asText() + "」" + (start == null ? "" : start.format(TIME)) + " 开始"
                    + (p.hasNonNull("location") ? "，" + p.path("location").asText() : "")
                    + goalOf(trigger.getUserId(), p.path("eventId").asLong());
        }).collect(Collectors.joining("\n"));
        return "提醒一下，你明天有 " + reminders.size() + " 个预约的活动：\n" + lines + "\n需要我帮忙的话，直接回复我。";
    }

    private String reminderNote(List<WakeTriggerEntity> reminders) {
        String about = reminders.stream().map(trigger -> {
            JsonNode p = payload(trigger);
            return "活动 #" + p.path("eventId").asLong() + "「" + p.path("title").asText() + "」（预约 #"
                    + p.path("reservationId").asLong() + "，" + WriteSupport.timeRange(time(p, "start"), time(p, "end")) + "）";
        }).collect(Collectors.joining("、"));
        return "（系统记录：下面这条是系统按模板替你自动发出的活动提醒，涉及：" + about + "。学生接下来的话可能是在回应它。）";
    }

    /** "这是目标「…」的一步。" when the event is a step of an active goal. */
    private String goalOf(String userId, long eventId) {
        return steps.findByUserIdAndKindAndRefId(userId, TandemGoalMilestoneEntity.Kind.EVENT, eventId).stream()
                .map(step -> activeGoal(userId, step.getGoalId()))
                .filter(Objects::nonNull)
                .map(goal -> "这是目标「" + goal.getTitle() + "」的一步。")
                .findFirst().orElse("");
    }

    private TandemGoalEntity activeGoal(String userId, long goalId) {
        return goals.findByIdAndUserId(goalId, userId)
                .filter(goal -> goal.getStatus() == TandemGoalEntity.Status.ACTIVE).orElse(null);
    }

    private static String numbered(List<String> facts) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            lines.add((i + 1) + ". " + facts.get(i));
        }
        return String.join("\n", lines);
    }

    private static LocalDateTime time(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        try {
            return value == null || "null".equals(value) ? null : LocalDateTime.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private JsonNode payload(WakeTriggerEntity trigger) {
        try {
            return objectMapper.readTree(trigger.getPayloadJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
