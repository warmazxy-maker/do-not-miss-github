package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RememberToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TandemMemoryRepository memories = mock(TandemMemoryRepository.class);
    private final RememberTool remember = new RememberTool(memories, mapper);
    private final ForgetTool forget = new ForgetTool(memories);
    private final ToolCaller caller = new ToolCaller("u1", "master", 99L);

    RememberToolTest() {
        when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savesANewFactWithItsSource() throws Exception {
        ToolResult result = remember.execute(mapper.readTree(
                "{\"category\":\"CONSTRAINT\",\"content\":\"这学期周末要打工\",\"quote\":\"我周末都在打工\",\"valid_until\":\"2027-03-31\"}"), caller);

        assertThat(result.status()).isEqualTo(ToolResultStatus.OK);
        assertThat(result.summaryForUser()).isEqualTo("记住了：这学期周末要打工");
        ArgumentCaptor<TandemMemoryEntity> saved = ArgumentCaptor.forClass(TandemMemoryEntity.class);
        verify(memories).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("u1");
        assertThat(saved.getValue().getSourceQuote()).isEqualTo("我周末都在打工");
        assertThat(saved.getValue().getValidUntil()).hasToString("2027-03-31");
    }

    @Test
    void refusesANewFactWhenFull() throws Exception {
        when(memories.countByUserId("u1")).thenReturn((long) RememberTool.MAX_MEMORIES);

        ToolResult result = remember.execute(mapper.readTree("{\"category\":\"PERSONAL\",\"content\":\"大三\"}"), caller);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.summaryForUser()).contains("满了");
        verify(memories, never()).save(any());
    }

    @Test
    void updatesInPlaceButOnlyTheStudentsOwnMemory() throws Exception {
        TandemMemoryEntity existing = TandemMemoryEntity.create("u1", TandemMemoryEntity.Category.PERSONAL, "日语 N2", null, null, null);
        when(memories.findByIdAndUserId(5L, "u1")).thenReturn(Optional.of(existing));
        when(memories.findByIdAndUserId(6L, "u1")).thenReturn(Optional.empty());

        ToolResult updated = remember.execute(mapper.readTree(
                "{\"category\":\"PERSONAL\",\"content\":\"日语 N1\",\"replaces_id\":5}"), caller);
        ToolResult foreign = remember.execute(mapper.readTree(
                "{\"category\":\"PERSONAL\",\"content\":\"x\",\"replaces_id\":6}"), caller);

        assertThat(updated.status()).isEqualTo(ToolResultStatus.OK);
        assertThat(existing.getContent()).isEqualTo("日语 N1");
        assertThat(foreign.status()).isEqualTo(ToolResultStatus.ERROR);
    }

    @Test
    void forgetsOnlyTheStudentsOwnMemory() throws Exception {
        TandemMemoryEntity existing = TandemMemoryEntity.create("u1", TandemMemoryEntity.Category.PREFERENCE, "不喜欢讲座", null, null, null);
        when(memories.findByIdAndUserId(5L, "u1")).thenReturn(Optional.of(existing));

        assertThat(forget.execute(mapper.readTree("{\"memory_id\":5}"), caller).status()).isEqualTo(ToolResultStatus.OK);
        assertThat(forget.execute(mapper.readTree("{\"memory_id\":6}"), caller).status()).isEqualTo(ToolResultStatus.ERROR);
        verify(memories).delete(existing);
    }
}
