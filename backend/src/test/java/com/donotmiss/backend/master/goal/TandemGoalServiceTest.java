package com.donotmiss.backend.master.goal;

import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.capabilityv2.CapabilityBucketEntity;
import com.donotmiss.backend.capabilityv2.CapabilityBucketRepository;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkEntity;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkRepository;
import com.donotmiss.backend.capabilityv2.CapabilityEvidenceLinkStatus;
import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.challenge.ChallengeRepository;
import com.donotmiss.backend.challenge.ChallengeService;
import com.donotmiss.backend.challenge.ChallengeStatus;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.reservation.ReservationEntity;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.donotmiss.backend.reservation.ReservationStatus;
import com.donotmiss.backend.schedule.ScheduleItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F3: progress read from the platform, the milestone rules, the active limit and the rollback guards. */
class TandemGoalServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "student";

    private final TandemGoalRepository goals = mock(TandemGoalRepository.class);
    private final TandemGoalMilestoneRepository milestones = mock(TandemGoalMilestoneRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ChallengeRepository challenges = mock(ChallengeRepository.class);
    private final CapabilityEvidenceLinkRepository links = mock(CapabilityEvidenceLinkRepository.class);
    private final CapabilityBucketRepository buckets = mock(CapabilityBucketRepository.class);
    private final TandemGoalService service = new TandemGoalService(goals, milestones, events, reservations, challenges,
            mock(ChallengeService.class), mock(ScheduleItemRepository.class), links, buckets, MAPPER, "Asia/Tokyo");

    private static <T> T withId(T entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private EventEntity event(long id, String title, LocalDateTime end) {
        EventEntity event = EventEntity.detached(id);
        event.setTitle(title);
        event.setStartTime(end.minusHours(2));
        event.setEndTime(end);
        when(events.findById(id)).thenReturn(Optional.of(event));
        return event;
    }

    private void reservation(EventEntity event, ReservationStatus status) {
        ReservationEntity reservation = new ReservationEntity();
        reservation.setUserId(USER);
        reservation.setEvent(event);
        reservation.setStatus(status);
        when(reservations.findByUserIdAndEventId(USER, event.getId())).thenReturn(Optional.of(reservation));
    }

    private ChallengeEntity challenge(long id, String title, ChallengeStatus status) {
        ChallengeEntity challenge = withId(new ChallengeEntity(), id);
        challenge.setUserId(USER);
        challenge.setTitle(title);
        challenge.setCategory("技能挑战");
        challenge.setGoal("目标");
        challenge.setDescription("描述");
        challenge.setStatus(status);
        when(challenges.findByIdAndUserId(id, USER)).thenReturn(Optional.of(challenge));
        return challenge;
    }

    private TandemGoalEntity goal(long id, String title, TandemGoalEntity.Status status) {
        TandemGoalEntity goal = withId(TandemGoalEntity.create(USER, null), id);
        goal.setTitle(title);
        goal.setGoalType(TandemGoalEntity.Type.ABILITY);
        goal.setStatus(status);
        when(goals.findByIdAndUserId(id, USER)).thenReturn(Optional.of(goal));
        return goal;
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void milestoneProgressFollowsWhatHappenedOnThePlatform() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        LocalDateTime future = LocalDateTime.now().plusDays(5);
        EventEntity attended = event(1, "日语会话角 第 1 次", past);
        reservation(attended, ReservationStatus.COMPLETED);
        EventEntity missed = event(2, "日语会话角 第 2 次", past);
        reservation(missed, ReservationStatus.RESERVED);
        event(3, "日语会话角 第 3 次", future);
        challenge(10, "每天背 30 个单词", ChallengeStatus.COMPLETED);
        challenge(11, "录一段自我介绍", ChallengeStatus.ACTIVE);
        TandemGoalEntity goal = goal(5, "口语能和日本人正常聊天", TandemGoalEntity.Status.ACTIVE);
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of(
                TandemGoalMilestoneEntity.of(5L, USER, 1, TandemGoalMilestoneEntity.Kind.EVENT, 1L, "会话角 1", null, false),
                TandemGoalMilestoneEntity.of(5L, USER, 2, TandemGoalMilestoneEntity.Kind.EVENT, 2L, "会话角 2", null, false),
                TandemGoalMilestoneEntity.of(5L, USER, 3, TandemGoalMilestoneEntity.Kind.EVENT, 3L, "会话角 3", null, false),
                TandemGoalMilestoneEntity.of(5L, USER, 4, TandemGoalMilestoneEntity.Kind.CHALLENGE, 10L, "背单词", null, true),
                TandemGoalMilestoneEntity.of(5L, USER, 5, TandemGoalMilestoneEntity.Kind.CHALLENGE, 11L, "自我介绍", null, true)));
        when(links.findBySourceTypeAndSourceId(anyString(), anyString())).thenReturn(List.of());

        TandemGoalService.GoalView view = service.view(goal);

        assertThat(view.milestones()).extracting(TandemGoalService.MilestoneView::stateLabel)
                .containsExactly("已参加", "已结束，没有签到", "还没预约", "已完成", "进行中");
        assertThat(view.done()).isEqualTo(2);
        assertThat(view.total()).isEqualTo(5);
    }

    @Test
    void growthShowsTheBucketsCompletedMilestonesGaveEvidenceTo() {
        EventEntity attended = event(1, "日语会话角", LocalDateTime.now().minusDays(1));
        reservation(attended, ReservationStatus.COMPLETED);
        TandemGoalEntity goal = goal(5, "练口语", TandemGoalEntity.Status.ACTIVE);
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of(
                TandemGoalMilestoneEntity.of(5L, USER, 1, TandemGoalMilestoneEntity.Kind.EVENT, 1L, "会话角", null, false)));
        CapabilityEvidenceLinkEntity scored = new CapabilityEvidenceLinkEntity();
        scored.setUserId(USER);
        scored.setBucketId(70L);
        scored.setStatus(CapabilityEvidenceLinkStatus.SCORED);
        CapabilityEvidenceLinkEntity someoneElse = new CapabilityEvidenceLinkEntity();
        someoneElse.setUserId("other");
        someoneElse.setBucketId(71L);
        someoneElse.setStatus(CapabilityEvidenceLinkStatus.SCORED);
        when(links.findBySourceTypeAndSourceId("ACTIVITY_COMPLETION", "1")).thenReturn(List.of(scored, someoneElse));
        CapabilityBucketEntity bucket = withId(new CapabilityBucketEntity(), 70L);
        bucket.setDisplayName("日语口语");
        when(buckets.findAllById(any())).thenReturn(List.of(bucket));

        assertThat(service.view(goal).growth()).containsExactly("日语口语");
    }

    @Test
    void eachMilestoneIsExactlyOnePlatformThingThatCanStillHappen() throws Exception {
        event(1, "已经结束的讲座", LocalDateTime.now().minusDays(1));
        event(2, "下周的会话角", LocalDateTime.now().plusDays(7));

        assertThatThrownBy(() -> service.resolveMilestones(USER, json("[{\"event_id\":1}]"), Set.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("已结束");
        assertThatThrownBy(() -> service.resolveMilestones(USER, json("[{\"event_id\":2,\"challenge_id\":3}]"), Set.of()))
                .hasMessageContaining("选一个");
        assertThatThrownBy(() -> service.resolveMilestones(USER, json("[{\"event_id\":2},{\"event_id\":2}]"), Set.of()))
                .hasMessageContaining("重复");

        List<com.fasterxml.jackson.databind.node.ObjectNode> steps = service.resolveMilestones(USER, json(
                "[{\"event_id\":2,\"note\":\"先开口\"},{\"new_challenge\":{\"title\":\"每天背 30 个单词\","
                        + "\"category\":\"考试挑战\",\"goal\":\"背完一册\",\"description\":\"每天打卡\"}}]"), Set.of());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("title").asText()).isEqualTo("下周的会话角");
        assertThat(steps.get(1).path("refId").isNull()).isTrue();
        assertThat(TandemGoalService.milestoneField(2, steps.get(1)).value()).startsWith("新挑战：每天背 30 个单词");
    }

    @Test
    void atMostThreeActiveGoals() {
        TandemGoalEntity a = goal(1, "a", TandemGoalEntity.Status.ACTIVE);
        TandemGoalEntity b = goal(2, "b", TandemGoalEntity.Status.ACTIVE);
        TandemGoalEntity c = goal(3, "c", TandemGoalEntity.Status.ACTIVE);
        TandemGoalEntity old = goal(4, "d", TandemGoalEntity.Status.ABANDONED);
        when(goals.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(a, b, c, old));

        assertThatThrownBy(() -> service.requireRoomForActive(USER, null)).hasMessageContaining("已经有 3 个");
        service.requireRoomForActive(USER, 3L);  // re-checking one of the three is fine
    }

    @Test
    void tandemSeesGoalsAndWhenEachWasLastReviewed() throws Exception {
        TandemGoalEntity stale = goal(1, "考过 N2", TandemGoalEntity.Status.ACTIVE);
        Field created = TandemGoalEntity.class.getDeclaredField("createdAt");
        created.setAccessible(true);
        created.set(stale, Instant.now().minus(10, ChronoUnit.DAYS));
        TandemGoalEntity fresh = goal(2, "每周一次线下活动", TandemGoalEntity.Status.ACTIVE);
        fresh.review("这周去了一次");
        when(goals.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(stale, fresh));
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(anyLong())).thenReturn(List.of());

        String block = service.contextBlock(USER);

        assertThat(block).contains("#1 [能力型] 考过 N2").contains("还没回顾过")
                .contains("上次回顾 0 天前（这周去了一次）")
                // F4: the review invitation arrives as a proactive message, not as a nudge here.
                .doesNotContain("超过 7 天没有回顾");
    }

    @Test
    void aSnapshotStillMatchesAfterARoundTripThroughTheDatabase() throws Exception {
        TandemGoalEntity goal = goal(5, "练口语", TandemGoalEntity.Status.ACTIVE);
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of(
                TandemGoalMilestoneEntity.of(5L, USER, 1, TandemGoalMilestoneEntity.Kind.EVENT, 65L, "会话角", null, false)));
        // Stored as text, read back: ids come back as int nodes, a fresh snapshot has long nodes.
        JsonNode stored = MAPPER.readTree(MAPPER.writeValueAsString(service.snapshot(goal)));

        assertThat(service.snapshot(goal).equals(stored)).isFalse();  // why plain equals was wrong
        assertThat(service.matches(goal, stored)).isTrue();
        goal.setTitle("练听力");
        assertThat(service.matches(goal, stored)).isFalse();
    }

    @Test
    void createGoalRollbackRefusesOnceTheGoalWasChangedByHand() throws Exception {
        TandemGoalEntity goal = goal(5, "练口语", TandemGoalEntity.Status.ACTIVE);
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of());
        JsonNode after = service.snapshot(goal);
        goal.setStatus(TandemGoalEntity.Status.COMPLETED);  // the student marked it done on the panel

        CreateGoalTool tool = new CreateGoalTool(service, MAPPER, "Asia/Tokyo");
        com.fasterxml.jackson.databind.node.ObjectNode undo = MAPPER.createObjectNode();
        undo.put("goalId", 5);
        undo.putArray("challenges");
        undo.set("after", after);

        assertThatThrownBy(() -> tool.undo(MAPPER.createObjectNode(), undo, USER)).hasMessageContaining("被改过了");
    }

    @Test
    void updateGoalCardShowsTheChangeAndRefusesNoChange() throws Exception {
        goal(5, "练口语", TandemGoalEntity.Status.ACTIVE);
        when(milestones.findByGoalIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of());
        UpdateGoalTool tool = new UpdateGoalTool(service, milestones, MAPPER);
        ToolCaller caller = new ToolCaller(USER, "master", 1L);

        assertThatThrownBy(() -> tool.propose(json("{\"goal_id\":5,\"title\":\"练口语\"}"), caller))
                .hasMessageContaining("没有要改的内容");
        var proposal = tool.propose(json("{\"goal_id\":5,\"status\":\"COMPLETED\"}"), caller);
        assertThat(proposal.card().kind()).isEqualTo("完成目标");
        assertThat(proposal.card().fields()).anySatisfy(field -> assertThat(field.value()).isEqualTo("进行中 → 已完成"));
    }
}
