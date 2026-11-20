package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.master.TandemMessageEntity;
import com.donotmiss.backend.master.TandemMessageRepository;
import com.donotmiss.backend.master.TandemService;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneRepository;
import com.donotmiss.backend.master.goal.TandemGoalRepository;
import com.donotmiss.backend.master.goal.TandemGoalService;
import com.donotmiss.backend.reservation.ReservationEntity;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.donotmiss.backend.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F4: what happens to a student's due triggers in one pass. */
class WakeProcessorTest {
    private static final String USER = "student";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final WakeTriggerRepository triggers = mock(WakeTriggerRepository.class);
    private final WakeSettingsRepository settings = mock(WakeSettingsRepository.class);
    private final TandemMessageRepository messages = mock(TandemMessageRepository.class);
    private final TandemService tandem = mock(TandemService.class);
    private final TandemGoalRepository goals = mock(TandemGoalRepository.class);
    private final TandemGoalMilestoneRepository steps = mock(TandemGoalMilestoneRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final AgentRegistry agents = mock(AgentRegistry.class);
    private final AgentLoop loop = mock(AgentLoop.class);
    private final WakeDrafts drafts = new WakeDrafts();
    private final WakeProcessor processor = new WakeProcessor(triggers, settings, messages, tandem, goals, steps,
            mock(TandemGoalService.class), reservations, agents, loop, drafts, MAPPER, "Asia/Tokyo", true);
    private final List<WakeTriggerEntity> waiting = new ArrayList<>();
    private final Instant now = Instant.now();

    WakeProcessorTest() {
        when(triggers.findByUserIdAndStatusAndProcessAfterLessThanEqualOrderByIdAsc(eq(USER), eq(WakeTriggerStatus.PENDING), any()))
                .thenReturn(waiting);
        when(triggers.transition(anyLong(), eq(WakeTriggerStatus.PENDING), eq(WakeTriggerStatus.PROCESSING))).thenReturn(1);
        when(triggers.findAllById(any())).thenReturn(waiting);
        when(settings.findById(USER)).thenReturn(Optional.empty());
        when(messages.save(any())).thenAnswer(call -> withId(call.getArgument(0), 900));
        when(steps.findByUserIdAndKindAndRefId(anyString(), any(), anyLong())).thenReturn(List.of());
        when(agents.find(WakeAgentSpec.NAME)).thenReturn(Optional.of(new WakeAgentSpec()));
    }

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

    private WakeTriggerEntity trigger(long id, WakeKind kind, String payload) {
        WakeTriggerEntity trigger = withId(WakeTriggerEntity.create(USER, kind, kind + ":" + id, payload, now, now), id);
        waiting.add(trigger);
        return trigger;
    }

    private void reservation(long id, ReservationStatus status) {
        ReservationEntity reservation = new ReservationEntity();
        reservation.setStatus(status);
        when(reservations.findById(id)).thenReturn(Optional.of(reservation));
    }

    private WakeTriggerEntity tomorrow() {
        reservation(25, ReservationStatus.RESERVED);
        return trigger(1, WakeKind.EVENT_TOMORROW, "{\"reservationId\":25,\"eventId\":65,\"title\":\"留学生日语会话角\","
                + "\"start\":\"2026-11-26T18:30\",\"end\":\"2026-11-26T20:00\",\"location\":\"国际交流会馆\"}");
    }

    private void modelAnswers(Long runId, String draft, String stopReason) {
        when(loop.run(any(), eq(USER), eq(USER), eq(AgentRunType.MASTER_WAKE), any(), anyString(), any()))
                .thenAnswer(call -> {
                    if (draft != null) {
                        drafts.put(runId, draft);
                    }
                    return new AgentLoop.Reply(runId, "ok", List.of(), List.of(), false, 1, stopReason);
                });
    }

    private TandemMessageEntity savedMessage() {
        ArgumentCaptor<TandemMessageEntity> saved = ArgumentCaptor.forClass(TandemMessageEntity.class);
        verify(messages).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aReminderIsFixedTextWithAHiddenNoteAndNoModel() {
        WakeTriggerEntity reminder = tomorrow();

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.SENT);

        TandemMessageEntity message = savedMessage();
        assertThat(message.isProactive()).isTrue();
        assertThat(message.getContent()).contains("「留学生日语会话角」明天 18:30 开始").contains("国际交流会馆");
        assertThat(message.getContextNote()).contains("活动 #65").contains("预约 #25").contains("按模板");
        assertThat(reminder.getStatus()).isEqualTo(WakeTriggerStatus.DONE);
        assertThat(reminder.getMessageId()).isEqualTo(900L);
        verify(loop, never()).run(any(), anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void whatTheStudentSwitchedOffIsSkipped() {
        WakeSettingsEntity off = WakeSettingsEntity.defaults(USER);
        off.update(true, List.of(WakeKind.EVENT_TOMORROW));
        when(settings.findById(USER)).thenReturn(Optional.of(off));
        WakeTriggerEntity reminder = tomorrow();

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.SKIPPED);
        assertThat(reminder.getStatus()).isEqualTo(WakeTriggerStatus.SKIPPED);
        verify(messages, never()).save(any());
    }

    @Test
    void aCancelledReservationIsNoLongerWorthAReminder() {
        WakeTriggerEntity reminder = tomorrow();
        reservation(25, ReservationStatus.CANCELLED);

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.SKIPPED);
        assertThat(reminder.getNote()).contains("情况已经变了");
    }

    @Test
    void pastTheDailyLimitEverythingMovesToTomorrowMorning() {
        WakeTriggerEntity reminder = tomorrow();
        when(messages.countByUserIdAndProactiveTrueAndCreatedAtGreaterThanEqual(eq(USER), any())).thenReturn(3L);

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.DEFERRED);
        assertThat(reminder.getStatus()).isEqualTo(WakeTriggerStatus.PENDING);
        assertThat(reminder.getProcessAfter())
                .isEqualTo(LocalDate.now(ZONE).plusDays(1).atTime(8, 0).atZone(ZONE).toInstant());
        verify(messages, never()).save(any());
    }

    @Test
    void severalThingsBecomeOneMessageWrittenByTandem() {
        WakeTriggerEntity checkIn = trigger(1, WakeKind.CHECK_IN, "{\"eventId\":65,\"title\":\"留学生日语会话角\"}");
        WakeTriggerEntity rank = trigger(2, WakeKind.RANK_UP,
                "{\"bucketId\":7,\"bucketName\":\"日语\",\"fromRank\":\"FOUNDATION\",\"toRank\":\"DEVELOPING\"}");
        modelAnswers(41L, "会话角签到成功啦，日语也升到了“成长中”。有空写几句心得吗？", "ANSWERED");

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.SENT);

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(loop).run(any(), eq(USER), eq(USER), eq(AgentRunType.MASTER_WAKE), any(), event.capture(), any());
        assertThat(event.getValue()).contains("不是学生发的消息").contains("1. 活动签到成功：「留学生日语会话角」（活动 #65）")
                .contains("2. 能力晋级：「日语」从「基础」升到「成长中」");
        TandemMessageEntity message = savedMessage();
        assertThat(message.getContent()).startsWith("会话角签到成功啦");
        assertThat(message.getRunId()).isEqualTo(41L);
        assertThat(message.getContextNote()).contains("被平台事件叫醒").contains("活动 #65");
        assertThat(List.of(checkIn.getStatus(), rank.getStatus())).containsOnly(WakeTriggerStatus.DONE);
    }

    @Test
    void tandemMayDecideToSayNothingButAReminderStillGoesOut() {
        WakeTriggerEntity checkIn = trigger(1, WakeKind.CHECK_IN, "{\"eventId\":65,\"title\":\"会话角\"}");
        WakeTriggerEntity reminder = tomorrow();
        modelAnswers(42L, null, "ANSWERED");

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.SENT);

        assertThat(checkIn.getStatus()).isEqualTo(WakeTriggerStatus.SKIPPED);
        assertThat(checkIn.getNote()).contains("不必说");
        assertThat(reminder.getStatus()).isEqualTo(WakeTriggerStatus.DONE);
        assertThat(savedMessage().getContent()).startsWith("提醒一下");
    }

    @Test
    void aModelFailureIsRetriedOnceThenGivenUp() {
        WakeTriggerEntity checkIn = trigger(1, WakeKind.CHECK_IN, "{\"eventId\":65,\"title\":\"会话角\"}");
        modelAnswers(43L, null, "MODEL_UNAVAILABLE");

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.RETRY);
        assertThat(checkIn.getStatus()).isEqualTo(WakeTriggerStatus.PENDING);
        assertThat(checkIn.getProcessAfter()).isEqualTo(now.plus(WakeProcessor.RETRY_AFTER));

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.RETRY);
        assertThat(checkIn.getStatus()).isEqualTo(WakeTriggerStatus.FAILED);
        verify(messages, never()).save(any());
    }

    @Test
    void aTriggerAnotherPassAlreadyClaimedIsLeftAlone() {
        tomorrow();
        when(triggers.transition(anyLong(), eq(WakeTriggerStatus.PENDING), eq(WakeTriggerStatus.PROCESSING))).thenReturn(0);

        assertThat(processor.processUser(USER, now)).isEqualTo(WakeProcessor.Outcome.NOTHING);
        verify(messages, never()).save(any());
    }
}
