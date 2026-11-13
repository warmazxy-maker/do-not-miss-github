package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.ai.ToolChat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
    private final TandemService service = new TandemService(messages, conversations, memories,
            mock(AgentRegistry.class), mock(AgentLoop.class), runService, llm, new ObjectMapper(), "Asia/Tokyo");

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

        assertThat(history).extracting(ToolChat.Message::role).containsExactly(
                ToolChat.Role.SYSTEM, ToolChat.Role.SYSTEM, ToolChat.Role.USER, ToolChat.Role.SYSTEM, ToolChat.Role.ASSISTANT);
        assertThat(history.get(0).content()).contains("#7 [约束] 周末要打工").doesNotContain("写心得");
        assertThat(history.get(1).content()).contains("学生在找后端方向的活动");
        assertThat(history.get(3).content()).contains("查日程（11/16～11/22） → 0 个安排").contains("原始数据没有保留");
        assertThat(history.get(4).content()).isEqualTo("下周没有安排。");
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
