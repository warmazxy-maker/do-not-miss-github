package com.donotmiss.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The response shapes seen in the 2026-11-12 experiment against the real gateway. */
class ToolChatParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsParallelToolCallsWithStringArguments() throws Exception {
        ToolChat.Turn turn = OpenAiCompatibleLlmClient.parseTurn(mapper.readTree("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":"\\n\\n","tool_calls":[
                  {"id":"call_a","type":"function","function":{"name":"get_capability_profile","arguments":"{}"}},
                  {"id":"call_b","type":"function","function":{"name":"get_reservations","arguments":"{\\"days\\":30}"}}
                ]}}]}
                """));
        assertThat(turn.wantsTools()).isTrue();
        assertThat(turn.toolCalls()).extracting(ToolChat.Call::name)
                .containsExactly("get_capability_profile", "get_reservations");
        assertThat(turn.toolCalls().get(1).argumentsJson()).isEqualTo("{\"days\":30}");
        assertThat(turn.content()).isEmpty();
    }

    @Test
    void readsAPlainAnswer() throws Exception {
        ToolChat.Turn turn = OpenAiCompatibleLlmClient.parseTurn(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":"你好呀","tool_calls":null}}]}
                """));
        assertThat(turn.wantsTools()).isFalse();
        assertThat(turn.content()).isEqualTo("你好呀");
    }

    @Test
    void emptyResponsesAreReportedNotSwallowed() throws Exception {
        assertThatThrownBy(() -> OpenAiCompatibleLlmClient.parseTurn(mapper.readTree("{\"choices\":[]}")))
                .isInstanceOf(ToolChat.UnavailableException.class);
        assertThatThrownBy(() -> OpenAiCompatibleLlmClient.parseTurn(mapper.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}")))
                .isInstanceOf(ToolChat.UnavailableException.class);
    }
}
