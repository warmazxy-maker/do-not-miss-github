package com.donotmiss.backend.agentkit.actions;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRegistry;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolResultStatus;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The control flow of confirmation cards. Database behaviour (the row lock of the compare-and-set,
 * the rollback of a failed confirmation) is Spring and MySQL's, and was checked in the browser.
 */
class PendingActionServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "student";
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    /** Runs the callback directly; enough to follow the flow, not to test real transactions. */
    private static final TransactionTemplate DIRECT = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        }
    };

    private final PendingActionRepository repository = mock(PendingActionRepository.class);
    private final AgentTraceArtifactService artifacts = mock(AgentTraceArtifactService.class);
    private final FakeWrite tool = new FakeWrite();
    private Set<Permission> permissions = Set.of(Permission.STUDENT_FEATURES);
    private final PendingActionService service = new PendingActionService(repository,
            new ToolRegistry(List.of(tool), List.of()), userId -> permissions, artifacts, MAPPER, DIRECT,
            Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));

    /** A write tool that records what it was asked to do. */
    private static final class FakeWrite implements ConfirmableTool {
        final List<JsonNode> applied = new ArrayList<>();
        RuntimeException failApply;
        RuntimeException failUndo;
        RuntimeException failPropose;

        @Override
        public ToolDefinition definition() {
            try {
                return new ToolDefinition("fake_write", 2, "假写入", "测试", MAPPER.readTree(
                        "{\"type\":\"object\",\"properties\":{\"replaces_card\":{\"type\":\"integer\"}}}"),
                        new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
                        Permission.STUDENT_FEATURES, null, Duration.ofSeconds(1));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public Proposal propose(JsonNode arguments, ToolCaller caller) {
            if (failPropose != null) {
                throw failPropose;
            }
            return new Proposal(new ActionCard("加日程", "英语角", List.of(new ActionCard.Field("时间", "周五 14:00")),
                    List.of(), List.of("和日程「打工」时间重叠"), "/student/schedule"), MAPPER.createObjectNode().put("title", "英语角"));
        }

        @Override
        public Applied apply(JsonNode payload, String userId) {
            if (failApply != null) {
                throw failApply;
            }
            applied.add(payload);
            return new Applied("已加到日程表", MAPPER.createObjectNode().put("itemId", 31));
        }

        @Override
        public String undo(JsonNode payload, JsonNode undoData, String userId) {
            if (failUndo != null) {
                throw failUndo;
            }
            return "已撤销";
        }
    }

    private static PendingActionEntity action(long id, PendingActionStatus status, Instant expiresAt) {
        PendingActionEntity entity = PendingActionEntity.proposed(USER, "master", 77L, "fake_write", 2,
                "{\"title\":\"英语角\"}", "{\"kind\":\"加日程\",\"headline\":\"英语角\"}", NOW.minusSeconds(60), expiresAt);
        entity.setStatus(status);
        try {
            Field field = PendingActionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return entity;
    }

    private PendingActionEntity stored(long id, PendingActionStatus status) {
        PendingActionEntity entity = action(id, status, NOW.plus(Duration.ofHours(23)));
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(entity));
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        return entity;
    }

    // ---- proposing ----

    @Test
    void aWriteCallBecomesAPendingCardAndTheModelHearsNothingRanYet() throws Exception {
        when(repository.save(any())).thenAnswer(call -> {
            PendingActionEntity saved = call.getArgument(0);
            Field field = PendingActionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(saved, 9L);
            return saved;
        });

        ToolResult result = service.propose(tool, MAPPER.createObjectNode(), new ToolCaller(USER, "master", 77L));

        assertThat(result.status()).isEqualTo(ToolResultStatus.PENDING_CONFIRMATION);
        assertThat(result.data().path("card_id").asLong()).isEqualTo(9L);
        assertThat(result.messageForModel()).contains("还没有执行").contains("不要说已经做好了").contains("提醒");
        assertThat(tool.applied).isEmpty();
        verify(repository).save(any());
    }

    @Test
    void aFailedFirstCheckMakesNoCard() throws Exception {
        tool.failPropose = ApiException.badRequest("你已经预约过这个活动了");

        ToolResult result = service.propose(tool, MAPPER.createObjectNode(), new ToolCaller(USER, "master", 77L));

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.summaryForUser()).contains("你已经预约过这个活动了");
        verify(repository, never()).save(any());
    }

    @Test
    void aNewCardCanReplaceAnOpenOne() throws Exception {
        when(repository.save(any())).thenAnswer(call -> {
            PendingActionEntity saved = call.getArgument(0);
            Field field = PendingActionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(saved, 10L);
            return saved;
        });
        when(repository.supersede(eq(4L), eq(10L), eq(USER), any(), any(), any())).thenReturn(1);

        ToolResult result = service.propose(tool, MAPPER.createObjectNode().put("replaces_card", 4),
                new ToolCaller(USER, "master", 77L));

        assertThat(result.messageForModel()).contains("第 4 号卡片已经作废");
    }

    // ---- confirming ----

    @Test
    void confirmingRunsTheFrozenPayload() {
        stored(5, PendingActionStatus.PENDING);
        when(repository.transition(5L, USER, PendingActionStatus.PENDING, PendingActionStatus.DONE, NOW)).thenReturn(1);
        when(repository.save(any())).thenAnswer(call -> {
            PendingActionEntity saved = call.getArgument(0);
            saved.setStatus(PendingActionStatus.DONE);
            return saved;
        });

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.message()).isEqualTo("已加到日程表");
        assertThat(tool.applied).singleElement().satisfies(payload -> assertThat(payload.path("title").asText()).isEqualTo("英语角"));
        assertThat(outcome.action().canUndo()).isTrue();
    }

    @Test
    void aSecondClickThatLosesTheRaceRunsNothing() {
        stored(5, PendingActionStatus.PENDING);
        when(repository.transition(anyLong(), anyString(), any(), any(), any())).thenReturn(0);

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        assertThat(tool.applied).isEmpty();
    }

    @Test
    void anAlreadyDecidedCardIsNotRunAgain() {
        stored(5, PendingActionStatus.CANCELLED);

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("已取消");
        verify(repository, never()).transition(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void whenTheServiceRefusesTheCardIsMarkedFailedWithItsReason() {
        stored(5, PendingActionStatus.PENDING);
        when(repository.transition(anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        tool.failApply = ApiException.badRequest("活动未上架或已结束，不能预约");

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("活动未上架或已结束");
        verify(repository).transition(5L, USER, PendingActionStatus.PENDING, PendingActionStatus.FAILED, NOW);
    }

    @Test
    void anOverdueCardExpiresInsteadOfRunning() {
        PendingActionEntity old = action(5, PendingActionStatus.PENDING, NOW.minusSeconds(1));
        when(repository.findByIdAndUserId(5L, USER)).thenReturn(Optional.of(old));

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.message()).contains("过期");
        assertThat(tool.applied).isEmpty();
        verify(repository).transition(5L, USER, PendingActionStatus.PENDING, PendingActionStatus.EXPIRED, NOW);
        assertThat(service.view(old, NOW).status()).isEqualTo("EXPIRED");
    }

    @Test
    void aStudentWhoLostThePermissionCannotConfirm() {
        stored(5, PendingActionStatus.PENDING);
        permissions = Set.of();
        when(repository.transition(anyLong(), anyString(), any(), any(), any())).thenReturn(1);

        PendingActionService.Outcome outcome = service.confirm(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("权限");
        assertThat(tool.applied).isEmpty();
    }

    // ---- rolling back ----

    @Test
    void rollbackRunsForADoneCard() {
        stored(5, PendingActionStatus.DONE);
        when(repository.markUndone(5L, USER, NOW, PendingActionStatus.DONE, PendingActionStatus.UNDONE)).thenReturn(1);

        PendingActionService.Outcome outcome = service.undo(USER, 5L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.message()).isEqualTo("已撤销");
    }

    @Test
    void aRefusedRollbackChangesNothingAndSaysWhy() {
        stored(5, PendingActionStatus.DONE);
        when(repository.markUndone(anyLong(), anyString(), any(), any(), any())).thenReturn(1);
        tool.failUndo = ApiException.badRequest("这条日程加上之后被改过了");

        PendingActionService.Outcome outcome = service.undo(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).isEqualTo("没能撤销：这条日程加上之后被改过了");
    }

    @Test
    void onlyADoneCardCanBeRolledBack() {
        stored(5, PendingActionStatus.PENDING);

        PendingActionService.Outcome outcome = service.undo(USER, 5L);

        assertThat(outcome.ok()).isFalse();
        verify(repository, never()).markUndone(anyLong(), anyString(), any(), any(), any());
    }
}
