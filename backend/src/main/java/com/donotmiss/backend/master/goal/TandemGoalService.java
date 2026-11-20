package com.donotmiss.backend.master.goal;

import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.tools.WriteSupport;
import com.donotmiss.backend.capabilityv2.CapabilityBucketEntity;
import com.donotmiss.backend.capabilityv2.CapabilityBucketRepository;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkEntity;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkRepository;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkStatus;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceSourceKind;
import com.donotmiss.backend.challenge.ChallengeDtos;
import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.challenge.ChallengeRepository;
import com.donotmiss.backend.challenge.ChallengeService;
import com.donotmiss.backend.challenge.ChallengeStatus;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.reservation.ReservationEntity;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.donotmiss.backend.reservation.ReservationStatus;
import com.donotmiss.backend.schedule.ScheduleItemRepository;
import com.donotmiss.backend.schedule.ScheduleItemStatus;
import com.donotmiss.backend.schedule.ScheduleItemType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F3: goals and their milestones.
 *
 * <ul>
 *   <li>A goal is free text plus a type (F3 decisions 1, 2). Capability buckets are not an input:
 *       they are shown as growth, found from the evidence its completed milestones produced.</li>
 *   <li>Every milestone is an event or a challenge (decision 3). Its state is read from the
 *       platform each time (checked in, challenge completed), never ticked by hand or by the model.</li>
 *   <li>Only the student completes or abandons a goal (decision 4); at most 3 are active (decision 5).</li>
 * </ul>
 *
 * <p>Changes by Tandem go through confirmation cards; the snapshot here is what makes them
 * reversible: a rollback runs only while the goal still looks exactly as the card left it.</p>
 */
@Service
public class TandemGoalService {
    public static final int MAX_ACTIVE = 3;
    public static final int MAX_MILESTONES = 8;
    /** Tandem may suggest a review once a goal has gone this long without one (decision 8). */
    public static final Duration REVIEW_AFTER = Duration.ofDays(7);
    static final int PAST_SHOWN = 5;
    static final List<String> CHALLENGE_CATEGORIES =
            List.of("技能挑战", "考试挑战", "身体挑战", "作品挑战", "生活挑战", "其他挑战");

    private final TandemGoalRepository goals;
    private final TandemGoalMilestoneRepository milestones;
    private final EventRepository events;
    private final ReservationRepository reservations;
    private final ChallengeRepository challenges;
    private final ChallengeService challengeService;
    private final ScheduleItemRepository scheduleItems;
    private final CapabilityEvidenceLinkRepository evidenceLinks;
    private final CapabilityBucketRepository buckets;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public TandemGoalService(TandemGoalRepository goals, TandemGoalMilestoneRepository milestones,
                             EventRepository events, ReservationRepository reservations,
                             ChallengeRepository challenges, ChallengeService challengeService,
                             ScheduleItemRepository scheduleItems, CapabilityEvidenceLinkRepository evidenceLinks,
                             CapabilityBucketRepository buckets, ObjectMapper objectMapper,
                             @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.goals = goals;
        this.milestones = milestones;
        this.events = events;
        this.reservations = reservations;
        this.challenges = challenges;
        this.challengeService = challengeService;
        this.scheduleItems = scheduleItems;
        this.evidenceLinks = evidenceLinks;
        this.buckets = buckets;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    // ---- views ----

    public record MilestoneView(Long id, String kind, String kindLabel, Long refId, String title, String note,
                                String state, String stateLabel, boolean done, String when) {
    }

    public record GoalView(Long id, String title, String description, String type, String typeLabel,
                           LocalDate targetDate, String status, String statusLabel, int done, int total,
                           List<MilestoneView> milestones, List<String> growth, Instant lastReviewedAt,
                           String lastReviewNote, Instant createdAt, Instant closedAt) {
    }

    public record Goals(List<GoalView> active, List<GoalView> past, int maxActive) {
    }

    @Transactional(readOnly = true)
    public Goals list(String userId) {
        List<TandemGoalEntity> all = goals.findByUserIdOrderByCreatedAtDesc(userId);
        List<GoalView> active = all.stream().filter(goal -> goal.getStatus() == TandemGoalEntity.Status.ACTIVE)
                .map(this::view).toList();
        List<GoalView> past = all.stream().filter(goal -> goal.getStatus() != TandemGoalEntity.Status.ACTIVE)
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .limit(PAST_SHOWN).map(this::view).toList();
        return new Goals(active, past, MAX_ACTIVE);
    }

    GoalView view(TandemGoalEntity goal) {
        List<MilestoneView> steps = milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId()).stream()
                .map(this::milestoneView).toList();
        int done = (int) steps.stream().filter(MilestoneView::done).count();
        return new GoalView(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getGoalType().name(),
                goal.getGoalType().label(), goal.getTargetDate(), goal.getStatus().name(), goal.getStatus().label(),
                done, steps.size(), steps, growth(goal.getUserId(), steps), goal.getLastReviewedAt(),
                goal.getLastReviewNote(), goal.getCreatedAt(), goal.getClosedAt());
    }

    /** Where a milestone stands, read from the platform (F3 decision 3). */
    MilestoneView milestoneView(TandemGoalMilestoneEntity step) {
        String state;
        String label;
        String when = null;
        if (step.getKind() == TandemGoalMilestoneEntity.Kind.EVENT) {
            EventEntity event = events.findById(step.getRefId()).orElse(null);
            ReservationEntity reservation = reservations.findByUserIdAndEventId(step.getUserId(), step.getRefId()).orElse(null);
            boolean ended = event == null || event.hasEnded(LocalDateTime.now(zone));
            if (event != null) {
                when = WriteSupport.timeRange(event.getStartTime(), event.getEndTime());
            }
            if (reservation != null && reservation.getStatus() == ReservationStatus.COMPLETED) {
                state = "DONE";
                label = "已参加";
            } else if (reservation != null && reservation.getStatus() == ReservationStatus.RESERVED) {
                state = ended ? "MISSED" : "RESERVED";
                label = ended ? "已结束，没有签到" : "已预约";
            } else if (event == null || ended || event.getReviewStatus() != EventReviewStatus.APPROVED) {
                state = "UNAVAILABLE";
                label = event == null ? "活动已不存在" : ended ? "活动已结束" : "活动已下架";
            } else {
                state = "TODO";
                label = "还没预约";
            }
        } else {
            ChallengeEntity challenge = challenges.findByIdAndUserId(step.getRefId(), step.getUserId()).orElse(null);
            if (challenge == null) {
                state = "UNAVAILABLE";
                label = "挑战已不存在";
            } else if (challenge.getStatus() == ChallengeStatus.COMPLETED) {
                state = "DONE";
                label = "已完成";
            } else if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
                state = "UNAVAILABLE";
                label = "挑战已取消";
            } else {
                state = "TODO";
                label = "进行中";
            }
        }
        return new MilestoneView(step.getId(), step.getKind().name(), step.getKind().label(), step.getRefId(),
                step.getTitle(), step.getNote(), state, label, "DONE".equals(state), when);
    }

    /**
     * Capability buckets the completed milestones gave evidence to (F3 decision 2): shown as
     * growth, never fed back into recommendations.
     */
    private List<String> growth(String userId, List<MilestoneView> steps) {
        Set<Long> bucketIds = new LinkedHashSet<>();
        for (MilestoneView step : steps) {
            if (!step.done()) {
                continue;
            }
            String kind = "EVENT".equals(step.kind()) ? CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION.name()
                    : CapabilityEvidenceSourceKind.CHALLENGE_COMPLETION.name();
            evidenceLinks.findBySourceTypeAndSourceId(kind, String.valueOf(step.refId())).stream()
                    .filter(link -> userId.equals(link.getUserId()))
                    .filter(link -> link.getStatus() == CapabilityEvidenceLinkStatus.SCORED)
                    .map(CapabilityEvidenceLinkEntity::getBucketId)
                    .forEach(bucketIds::add);
        }
        if (bucketIds.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = buckets.findAllById(bucketIds).stream()
                .collect(Collectors.toMap(CapabilityBucketEntity::getId, CapabilityBucketEntity::getDisplayName));
        return bucketIds.stream().map(names::get).filter(Objects::nonNull).distinct().toList();
    }

    /** What Tandem sees every turn, like its memories (F3 decision 8). */
    @Transactional(readOnly = true)
    public String contextBlock(String userId) {
        Goals all = list(userId);
        if (all.active().isEmpty() && all.past().isEmpty()) {
            return "【学生的目标】\n（还没有。学生说出想达成的事时，可以和他一起定一个目标。）";
        }
        StringBuilder text = new StringBuilder("【学生的目标】（进行中 " + all.active().size() + " 个，最多 "
                + MAX_ACTIVE + " 个；编号用于修改）\n");
        Instant now = Instant.now();
        List<String> dueForReview = new ArrayList<>();
        for (GoalView goal : all.active()) {
            text.append("#").append(goal.id()).append(" [").append(goal.typeLabel()).append("] ").append(goal.title());
            if (goal.targetDate() != null) {
                text.append("（目标日期 ").append(goal.targetDate()).append("）");
            }
            text.append(" 进度 ").append(goal.done()).append("/").append(goal.total());
            Instant since = goal.lastReviewedAt() == null ? goal.createdAt() : goal.lastReviewedAt();
            long days = Duration.between(since, now).toDays();
            text.append("；").append(goal.lastReviewedAt() == null ? "还没回顾过" : "上次回顾 " + days + " 天前");
            if (goal.lastReviewNote() != null) {
                text.append("（").append(goal.lastReviewNote()).append("）");
            }
            text.append("\n");
            if (goal.description() != null) {
                text.append("   说明：").append(goal.description()).append("\n");
            }
            for (int i = 0; i < goal.milestones().size(); i++) {
                MilestoneView step = goal.milestones().get(i);
                text.append("   ").append(i + 1).append(". [里程碑 #").append(step.id()).append("] ")
                        .append(step.kindLabel()).append(" #").append(step.refId()).append(" ").append(step.title())
                        .append(step.when() == null ? "" : "（" + step.when() + "）")
                        .append(" —— ").append(step.stateLabel()).append("\n");
            }
            if (!goal.growth().isEmpty()) {
                text.append("   已经带来证据的能力：").append(String.join("、", goal.growth())).append("\n");
            }
            if (!since.plus(REVIEW_AFTER).isAfter(now)) {
                dueForReview.add("#" + goal.id());
            }
        }
        if (!all.past().isEmpty()) {
            text.append("过去的目标：").append(all.past().stream()
                    .map(goal -> "#" + goal.id() + " " + goal.title() + "（" + goal.statusLabel() + "，进度 "
                            + goal.done() + "/" + goal.total() + "）")
                    .collect(Collectors.joining("；"))).append("\n");
        }
        if (!dueForReview.isEmpty()) {
            text.append("目标 ").append(String.join("、", dueForReview))
                    .append(" 超过 7 天没有回顾了：合适的时候问学生一句要不要一起看看进展，只问一次，学生没接话就不再提。");
        }
        return text.toString().trim();
    }

    // ---- milestones from the model's arguments ----

    /**
     * Checks the milestones a card proposes (first check, F2 decision 5) and turns them into the
     * frozen payload. Each item is exactly one of an existing event, an existing challenge or a
     * new challenge.
     */
    public List<ObjectNode> resolveMilestones(String userId, JsonNode items, Set<String> alreadyIn) {
        List<ObjectNode> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>(alreadyIn);
        int index = 0;
        for (JsonNode item : items) {
            index++;
            int given = (item.has("event_id") ? 1 : 0) + (item.has("challenge_id") ? 1 : 0) + (item.has("new_challenge") ? 1 : 0);
            if (given != 1) {
                throw ApiException.badRequest("第 " + index + " 个里程碑要在活动、已有挑战、新挑战三者中选一个");
            }
            ObjectNode step = objectMapper.createObjectNode();
            String note = WriteSupport.blankToNull(WriteSupport.text(item, "note"));
            step.put("note", note);
            if (item.has("event_id")) {
                long eventId = item.path("event_id").asLong();
                EventEntity event = events.findById(eventId)
                        .orElseThrow(() -> ApiException.badRequest("活动 #" + eventId + " 不存在"));
                if (event.getReviewStatus() != EventReviewStatus.APPROVED || event.hasEnded(LocalDateTime.now(zone))) {
                    throw ApiException.badRequest("活动「" + event.getTitle() + "」未上架或已结束，不能作为里程碑");
                }
                step.put("kind", TandemGoalMilestoneEntity.Kind.EVENT.name());
                step.put("refId", eventId);
                step.put("title", event.getTitle());
                step.put("when", WriteSupport.timeRange(event.getStartTime(), event.getEndTime()));
            } else if (item.has("challenge_id")) {
                long challengeId = item.path("challenge_id").asLong();
                ChallengeEntity challenge = challenges.findByIdAndUserId(challengeId, userId)
                        .orElseThrow(() -> ApiException.badRequest("你的挑战里没有 #" + challengeId));
                if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
                    throw ApiException.badRequest("挑战「" + challenge.getTitle() + "」已经取消了");
                }
                step.put("kind", TandemGoalMilestoneEntity.Kind.CHALLENGE.name());
                step.put("refId", challengeId);
                step.put("title", challenge.getTitle());
            } else {
                JsonNode spec = item.path("new_challenge");
                ObjectNode challenge = step.putObject("newChallenge");
                for (String field : List.of("title", "category", "goal", "description")) {
                    String value = WriteSupport.blankToNull(WriteSupport.text(spec, field));
                    if (value == null) {
                        throw ApiException.badRequest("第 " + index + " 个里程碑的新挑战缺少" + field);
                    }
                    challenge.put(field, value);
                }
                step.put("kind", TandemGoalMilestoneEntity.Kind.CHALLENGE.name());
                step.putNull("refId");
                step.put("title", challenge.path("title").asText());
            }
            String key = step.path("kind").asText() + ":" + step.path("refId").asText("new" + index);
            if (!seen.add(key)) {
                throw ApiException.badRequest("「" + step.path("title").asText() + "」重复了");
            }
            resolved.add(step);
        }
        return resolved;
    }

    /** One card line per milestone, e.g. "活动：留学生日语会话角（11月26日 18:30–20:00）". */
    public static ActionCard.Field milestoneField(int number, JsonNode step) {
        String kind = "EVENT".equals(step.path("kind").asText()) ? "活动"
                : step.has("newChallenge") ? "新挑战" : "挑战";
        String when = step.hasNonNull("when") ? "（" + step.path("when").asText() + "）" : "";
        String note = step.hasNonNull("note") ? " —— " + step.path("note").asText() : "";
        return new ActionCard.Field("里程碑 " + number, kind + "：" + step.path("title").asText() + when + note);
    }

    // ---- applying cards (inside the confirmation transaction) ----

    public void requireRoomForActive(String userId, Long except) {
        long active = goals.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(goal -> goal.getStatus() == TandemGoalEntity.Status.ACTIVE)
                .filter(goal -> !goal.getId().equals(except))
                .count();
        if (active >= MAX_ACTIVE) {
            throw ApiException.badRequest("进行中的目标已经有 " + MAX_ACTIVE + " 个了，先完成或放弃一个再新建");
        }
    }

    public TandemGoalEntity load(String userId, Long goalId) {
        return goals.findByIdAndUserId(goalId, userId).orElseThrow(() -> ApiException.notFound("没有这个目标（#" + goalId + "）"));
    }

    /** Creates the goal and its milestones; returns the ids of challenges it created. */
    public Created create(String userId, Long runId, JsonNode payload) {
        requireRoomForActive(userId, null);
        TandemGoalEntity goal = TandemGoalEntity.create(userId, runId);
        applyFields(goal, payload);
        goal = goals.save(goal);
        List<ObjectNode> createdChallenges = addMilestones(goal, payload.path("milestones"), 0);
        return new Created(goal.getId(), createdChallenges);
    }

    /** @param challenges for each challenge created: its id and how it looked right after */
    public record Created(Long goalId, List<ObjectNode> challenges) {
    }

    /** Adds milestones after position {@code start}; new challenges are created through the normal service. */
    public List<ObjectNode> addMilestones(TandemGoalEntity goal, JsonNode steps, int start) {
        List<ObjectNode> created = new ArrayList<>();
        int position = start;
        for (JsonNode step : steps) {
            TandemGoalMilestoneEntity.Kind kind = TandemGoalMilestoneEntity.Kind.valueOf(step.path("kind").asText());
            Long refId = step.path("refId").isIntegralNumber() ? step.path("refId").asLong() : null;
            boolean createdChallenge = step.path("createdChallenge").asBoolean(false);
            if (step.has("newChallenge")) {
                JsonNode spec = step.path("newChallenge");
                ChallengeDtos.ChallengeResponse challenge = challengeService.create(goal.getUserId(),
                        new ChallengeDtos.CreateChallengeRequest(spec.path("title").asText(), spec.path("category").asText(),
                                spec.path("goal").asText(), spec.path("description").asText()));
                refId = challenge.id();
                createdChallenge = true;
                ObjectNode record = objectMapper.createObjectNode();
                record.put("id", challenge.id());
                record.set("after", challengeSnapshot(challenge.title(), challenge.category(), challenge.goal(),
                        challenge.description(), challenge.status()));
                created.add(record);
            }
            milestones.save(TandemGoalMilestoneEntity.of(goal.getId(), goal.getUserId(), ++position, kind, refId,
                    step.path("title").asText(), WriteSupport.text(step, "note"), createdChallenge));
        }
        goal.touch();
        return created;
    }

    public void applyFields(TandemGoalEntity goal, JsonNode fields) {
        if (fields.has("title")) {
            goal.setTitle(fields.path("title").asText());
        }
        if (fields.has("description")) {
            goal.setDescription(WriteSupport.text(fields, "description"));
        }
        if (fields.has("goalType")) {
            goal.setGoalType(TandemGoalEntity.Type.valueOf(fields.path("goalType").asText()));
        }
        if (fields.has("targetDate")) {
            String date = WriteSupport.text(fields, "targetDate");
            goal.setTargetDate(date == null ? null : LocalDate.parse(date));
        }
        goal.touch();
    }

    /**
     * How a goal looks, for the rollback guard: everything Tandem or the student can change,
     * milestones by content (not by id, since a rollback recreates them).
     */
    public ObjectNode snapshot(TandemGoalEntity goal) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", goal.getTitle());
        node.put("description", goal.getDescription());
        node.put("goalType", goal.getGoalType().name());
        node.put("targetDate", goal.getTargetDate() == null ? null : goal.getTargetDate().toString());
        node.put("status", goal.getStatus().name());
        ArrayNode steps = node.putArray("milestones");
        for (TandemGoalMilestoneEntity step : milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId())) {
            ObjectNode item = steps.addObject();
            item.put("kind", step.getKind().name());
            item.put("refId", step.getRefId());
            item.put("title", step.getTitle());
            item.put("note", step.getNote());
            item.put("createdChallenge", step.isCreatedChallenge());
        }
        return node;
    }

    /**
     * True while the goal still looks exactly like a stored snapshot. The comparison goes through
     * JSON text on both sides: a stored snapshot comes back with ids as int nodes, a fresh one has
     * long nodes, and Jackson does not consider those equal.
     */
    public boolean matches(TandemGoalEntity goal, JsonNode stored) {
        try {
            return objectMapper.readTree(snapshot(goal).toString()).equals(objectMapper.readTree(stored.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Puts a goal back as a snapshot describes it, milestones included. */
    public void restore(TandemGoalEntity goal, JsonNode snapshot) {
        applyFields(goal, snapshot);
        TandemGoalEntity.Status status = TandemGoalEntity.Status.valueOf(snapshot.path("status").asText());
        if (status == TandemGoalEntity.Status.ACTIVE && goal.getStatus() != TandemGoalEntity.Status.ACTIVE) {
            requireRoomForActive(goal.getUserId(), goal.getId());
        }
        goal.setStatus(status);
        milestones.deleteAll(milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId()));
        milestones.flush();
        addMilestones(goal, snapshot.path("milestones"), 0);
    }

    public void deleteWithMilestones(TandemGoalEntity goal) {
        milestones.deleteAll(milestones.findByGoalIdOrderByPositionAscIdAsc(goal.getId()));
        goals.delete(goal);
    }

    /**
     * Deletes challenges a card created, as part of its rollback; refuses (and so rolls the whole
     * rollback back) if any was used since: completed, changed, or put on the schedule.
     */
    public void deleteCreatedChallenges(String userId, JsonNode created) {
        List<ChallengeEntity> toDelete = new ArrayList<>();
        for (JsonNode record : created) {
            ChallengeEntity challenge = challenges.findByIdAndUserId(record.path("id").asLong(), userId).orElse(null);
            if (challenge == null) {
                continue;
            }
            ObjectNode now = challengeSnapshot(challenge.getTitle(), challenge.getCategory(), challenge.getGoal(),
                    challenge.getDescription(), challenge.getStatus().name());
            if (!now.equals(record.path("after"))) {
                throw ApiException.badRequest("一起建的挑战「" + challenge.getTitle() + "」已经完成、取消或改过了，不能整体撤销");
            }
            boolean onSchedule = scheduleItems.findByUserIdAndItemTypeAndSourceIdOrderByStartTimeAsc(
                            userId, ScheduleItemType.CHALLENGE, challenge.getId()).stream()
                    .anyMatch(item -> item.getStatus() == ScheduleItemStatus.ACTIVE);
            if (onSchedule) {
                throw ApiException.badRequest("一起建的挑战「" + challenge.getTitle() + "」已经排进日程了，请先到日程页处理那一条");
            }
            toDelete.add(challenge);
        }
        challenges.deleteAll(toDelete);
    }

    private ObjectNode challengeSnapshot(String title, String category, String goal, String description, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", title);
        node.put("category", category);
        node.put("goal", goal);
        node.put("description", description);
        node.put("status", status);
        return node;
    }

    // ---- the student's own controls (F3 decision 7: no card needed) ----

    @Transactional
    public GoalView close(String userId, Long goalId, TandemGoalEntity.Status status) {
        if (status == TandemGoalEntity.Status.ACTIVE) {
            throw ApiException.badRequest("只能标为完成或放弃");
        }
        TandemGoalEntity goal = load(userId, goalId);
        goal.setStatus(status);
        return view(goal);
    }

    @Transactional
    public GoalView reopen(String userId, Long goalId) {
        TandemGoalEntity goal = load(userId, goalId);
        if (goal.getStatus() != TandemGoalEntity.Status.ACTIVE) {
            requireRoomForActive(userId, goalId);
            goal.setStatus(TandemGoalEntity.Status.ACTIVE);
        }
        return view(goal);
    }

    @Transactional
    public GoalView removeMilestone(String userId, Long goalId, Long milestoneId) {
        TandemGoalEntity goal = load(userId, goalId);
        TandemGoalMilestoneEntity step = milestones.findById(milestoneId)
                .filter(found -> found.getGoalId().equals(goal.getId()))
                .orElseThrow(() -> ApiException.notFound("这个目标里没有这个里程碑"));
        milestones.delete(step);
        goal.touch();
        return view(goal);
    }

    /** Deletes the goal only; challenges it created stay, they are the student's now. */
    @Transactional
    public void delete(String userId, Long goalId) {
        deleteWithMilestones(load(userId, goalId));
    }

    @Transactional
    public void review(String userId, Long goalId, String note) {
        load(userId, goalId).review(note);
    }
}
