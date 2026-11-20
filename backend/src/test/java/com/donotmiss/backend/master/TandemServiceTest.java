package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.ai.ToolChat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.actions.ActionView;
import com.donotmiss.backend.agentkit.actions.PendingActionService;
import com.donotmiss.backend.master.goal.TandemGoalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TandemServiceTest {
    private static final String USER = "u1";

    private final TandemMessageRepository messages = mock(TandemMessageRepository.class);
    private final TandemConversationRepository conversations = mock(TandemConversationRepository.class);
    private final TandemMemoryRepository memories = mock(TandemMemoryRepository.class);
    private final AgentRunService runService = mock(AgentRunService.class);
    private final OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
    private final PendingActionService pendingActions = mock(PendingActionService.class);
    private final TandemGoalService goals = mock(TandemGoalService.class);
    private final TandemService service = new TandemService(messages, conversations, memories,
            mock(AgentRegistry.class), mock(AgentLoop.class), runService, llm, pendingActions, goals, new ObjectMapper(), "Asia/Tokyo");

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

    private static TandemMessageEntity user(long id, String text) {
        return withId(TandemMessageEntity.user(USER, text), id);
    }

    private static TandemMessageEntity assistant(long id, String text, String stepsJson) {
        return withId(TandemMessageEntity.assistant(USER, text, stepsJson, "[]", false, null), id);
    }

    @Test
    void historyIsMemoriesThenSummaryThenRecentMessagesWithToolNotes() {
        TandemConversationEntity conversation = TandemConversationEntity.empty(USER);
        conversation.fold("学生在找后端方向的活动。", 10L);
        when(conversations.findById(USER)).thenReturn(Optional.of(conversation));
        when(goals.contextBlock(USER)).thenReturn("【学生的目标】考过 N2");
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(
                withId(TandemMemoryEntity.create(USER, TandemMemoryEntity.Category.CONSTRAINT, "周末要打工", null, null, null), 7),
                withId(TandemMemoryEntity.create(USER, TandemMemoryEntity.Category.COMMITMENT, "上周说好写心得", null, null,
                        LocalDate.of(2020, 1, 1)), 8)));
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 10L)).thenReturn(List.of(
                user(11, "下周有什么安排？"),
                assistant(12, "下周没有安排。",
                        "[{\"tool\":\"get_schedule\",\"title\":\"查日程\",\"detail\":\"11/16～11/22\",\"status\":\"OK\",\"summary\":\"0 个安排\"}]"),
                user(13, "那帮我找点活动")));

        List<ToolChat.Message> history = service.history(USER, 13L);

        assertThat(history).extracting(ToolChat.Message::role).containsExactly(ToolChat.Role.SYSTEM,
                ToolChat.Role.SYSTEM, ToolChat.Role.SYSTEM, ToolChat.Role.USER, ToolChat.Role.SYSTEM, ToolChat.Role.ASSISTANT);
        assertThat(history.get(0).content()).contains("#7 [约束] 周末要打工").doesNotContain("写心得");
        assertThat(history.get(1).content()).isEqualTo("【学生的目标】考过 N2");
        assertThat(history.get(2).content()).contains("学生在找后端方向的活动");
        assertThat(history.get(4).content()).contains("查日程（11/16～11/22） → 0 个安排").contains("原始数据没有保留");
        assertThat(history.get(5).content()).isEqualTo("下周没有安排。");
    }

    @Test
    void historyTellsTandemWhereItsCardsStandNow() {
        when(conversations.findById(USER)).thenReturn(Optional.empty());
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of());
        TandemMessageEntity answer = withId(TandemMessageEntity.assistant(USER, "我准备好了，你看一下卡片。", "[]", "[]", false, 99L), 2);
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 0L)).thenReturn(List.of(user(1, "帮我约那个日语角"), answer,
                user(3, "确认了吗")));
        ActionCard card = new ActionCard("预约活动", "日语口语角", List.of(), List.of(), List.of(), null);
        when(pendingActions.viewsByRun(USER, List.of(99L))).thenReturn(Map.of(99L, List.of(
                new ActionView(5L, "reserve_event", card, "DONE", "已执行", "已预约，也加进了日程表", null, false, true),
                new ActionView(6L, "add_schedule_item", card, "PENDING", "等待确认", null, null, true, false))));

        List<ToolChat.Message> history = service.history(USER, 3L);

        String note = history.stream().map(ToolChat.Message::content).filter(text -> text != null && text.contains("确认卡片")).findFirst().orElseThrow();
        assertThat(note).contains("第 5 号 预约活动「日语口语角」——已执行（已预约，也加进了日程表）")
                .contains("第 6 号").contains("等待确认");
    }

    @Test
    void aProactiveMessageComesBackWithItsHiddenNote() {
        when(conversations.findById(USER)).thenReturn(Optional.empty());
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of());
        TandemMessageEntity reminder = withId(TandemMessageEntity.proactive(USER, "提醒一下：会话角明天 18:30 开始。",
                "（系统记录：按模板发出的提醒，涉及活动 #65）", null), 2);
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 0L)).thenReturn(List.of(reminder, user(3, "帮我加进日程")));

        List<ToolChat.Message> history = service.history(USER, 3L);

        int note = history.stream().map(ToolChat.Message::content).toList().indexOf("（系统记录：按模板发出的提醒，涉及活动 #65）");
        assertThat(note).isGreaterThan(0);
        assertThat(history.get(note + 1).content()).startsWith("提醒一下");
        assertThat(service.view(reminder, java.util.Map.of()).unread()).isTrue();
    }

    @Test
    void clearingVoidsOpenCards() {
        service.clear(USER);
        verify(pendingActions).expireOpen(USER, MasterAgentSpec.NAME);
    }

    @Test
    void emptyMemoryTellsTandemHowToStart() {
        when(memories.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of());
        assertThat(service.memoryBlock(USER)).contains("还没有").contains("remember");
    }

    private List<TandemMessageEntity> conversationOf(int count) {
        List<TandemMessageEntity> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(i % 2 == 1 ? user(i, "问题" + i) : assistant(i, "回答" + i, null));
        }
        return list;
    }

    @Test
    void foldsAllButTheRecentWindowIntoTheSummary() {
        when(conversations.findById(USER)).thenReturn(Optional.empty());
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 0L)).thenReturn(conversationOf(30));
        when(llm.chatPlain(anyString())).thenReturn(Optional.of("摘要内容"));

        assertThat(service.compressIfNeeded(USER)).isTrue();

        ArgumentCaptor<TandemConversationEntity> saved = ArgumentCaptor.forClass(TandemConversationEntity.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().getSummary()).isEqualTo("摘要内容");
        assertThat(saved.getValue().getSummarizedUpTo()).isEqualTo(30L - TandemService.RECENT_WINDOW);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chatPlain(prompt.capture());
        assertThat(prompt.getValue()).contains("学生：问题1").contains("你：回答18").doesNotContain("问题19");
    }

    @Test
    void keepsEverythingWhenTheModelFails() {
        when(conversations.findById(USER)).thenReturn(Optional.empty());
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 0L)).thenReturn(conversationOf(30));
        when(llm.chatPlain(anyString())).thenReturn(Optional.empty());

        assertThat(service.compressIfNeeded(USER)).isFalse();
        verify(conversations, never()).save(any());
        verify(runService).failRun(any(), any());
    }

    @Test
    void shortConversationsAreLeftAlone() {
        when(conversations.findById(USER)).thenReturn(Optional.empty());
        when(messages.findByUserIdAndIdGreaterThanOrderByIdAsc(USER, 0L)).thenReturn(conversationOf(10));

        assertThat(service.compressIfNeeded(USER)).isFalse();
        verify(llm, never()).chatPlain(anyString());
    }

    @Test
    void clearingKeepsLongTermMemories() {
        service.clear(USER);
        verify(messages).deleteAllOfUser(eq(USER));
        verify(conversations).deleteById(eq(USER));
        verify(memories, never()).deleteAll();
    }
}
