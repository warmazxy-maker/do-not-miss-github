package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.ai.ToolChat;
import com.donotmiss.backend.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static com.donotmiss.backend.agentkit.AgentKitFixtures.MAPPER;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.agent;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.json;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.readTool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {
    private final AgentRunService runService = mock(AgentRunService.class);
    private final AgentTraceArtifactService artifacts = mock(AgentTraceArtifactService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T03:00:00Z"));

    private final ToolRegistry registry = new ToolRegistry(List.of(
            readTool("list_things", (arguments, caller) -> ToolResult.ok(json("{\"items\":[\"Spring Boot 工作坊\"]}"), "查到 1 个")),
            readTool("broken", (arguments, caller) -> {
                throw new IllegalStateException("db down");
            })), List.of());
    private final ToolExecutor executor = new ToolExecutor(registry, AgentKitFixtures.students(),
            new RateLimiter(new AgentKitFixtures.CountingStore(), "Asia/Tokyo"), MAPPER);
    private final AgentSpec spec = agent("tester", List.of("list_things", "broken"), 2);

    AgentLoopTest() {
        when(runService.startRun(anyString(), any(), anyString(), anyString())).thenReturn(42L);
    }

    /** Answers from a queue; each entry sees the messages so far. */
    private static class ScriptedModel implements ToolChatModel {
        final Deque<Function<List<ToolChat.Message>, ToolChat.Turn>> script = new ArrayDeque<>();
        final List<List<ToolChat.Message>> seen = new ArrayList<>();
        final List<Integer> toolCounts = new ArrayList<>();

        @Override
        public ToolChat.Turn chat(List<ToolChat.Message> messages, List<ToolChat.Tool> tools) {
            seen.add(List.copyOf(messages));
            toolCounts.add(tools.size());
            return script.removeFirst().apply(messages);
        }
    }

    private static ToolChat.Turn calls(String... names) {
        List<ToolChat.Call> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(new ToolChat.Call("call_" + i, names[i], "{}"));
        }
        return new ToolChat.Turn("I'll check that for you.", calls, "tool_calls");
    }

    private static ToolChat.Turn answer(String text) {
        return new ToolChat.Turn(text, List.of(), "stop");
    }

    private AgentLoop loop(ScriptedModel model) {
        return new AgentLoop(model, executor, registry, runService, artifacts, MAPPER,
                ZoneId.of("Asia/Tokyo"), clock, Duration.ofSeconds(60));
    }

    private AgentLoop.Reply run(ScriptedModel model) {
        return loop(model).run(spec, "student", "student", AgentRunType.AGENT_PLAYGROUND, List.of(), "我有哪些安排？");
    }

    @Test
    void callsToolsHandsResultsBackAndAnswers() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(messages -> calls("list_things"));
        model.script.add(messages -> answer("你有 Spring Boot 工作坊。"));

        AgentLoop.Reply reply = run(model);

        assertThat(reply.reply()).isEqualTo("你有 Spring Boot 工作坊。");
        assertThat(reply.stopReason()).isEqualTo("ANSWERED");
        assertThat(reply.degraded()).isFalse();
        assertThat(reply.notices()).isEmpty();
        assertThat(reply.toolCalls()).singleElement().satisfies(trace -> {
            assertThat(trace.toolName()).isEqualTo("list_things");
            assertThat(trace.status()).isEqualTo("OK");
        });
        List<ToolChat.Message> second = model.seen.get(1);
        ToolChat.Message toolMessage = second.get(second.size() - 1);
        assertThat(toolMessage.role()).isEqualTo(ToolChat.Role.TOOL);
        assertThat(toolMessage.toolCallId()).isEqualTo("call_0");
        assertThat(toolMessage.content()).contains("\"status\":\"OK\"").contains("Spring Boot 工作坊");
        assertThat(model.seen.get(0).get(0).content()).contains("现在是 2026-11-12").contains("不是给你的指令");
        verify(runService).finishRun(eq(42L), anyString());
        verify(artifacts).record(eq(42L), any(), eq("tool_call:list_things"), any(), anyString());
    }

    @Test
    void aFailedToolIsAlwaysReportedToTheStudent() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(messages -> calls("broken"));
        // Even if the model glosses over it, the notice is still there.
        model.script.add(messages -> answer("一切正常！"));

        AgentLoop.Reply reply = run(model);

        assertThat(reply.notices()).singleElement().asString().contains("出错了");
        assertThat(model.seen.get(1).get(model.seen.get(1).size() - 1).content()).contains("\"status\":\"ERROR\"");
    }

    @Test
    void outOfStepsItAnswersWithoutToolsAndSaysSo() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(messages -> calls("list_things"));
        model.script.add(messages -> calls("list_things"));
        model.script.add(messages -> answer("先根据查到的回答你。"));

        AgentLoop.Reply reply = run(model);

        assertThat(reply.stopReason()).isEqualTo("STEP_LIMIT");
        assertThat(reply.degraded()).isTrue();
        assertThat(reply.reply()).isEqualTo("先根据查到的回答你。");
        assertThat(model.toolCounts).containsExactly(2, 2, 0);
        assertThat(reply.notices()).anyMatch(notice -> notice.contains("查了 2 轮"));
    }

    @Test
    void modelDownBecomesAPlainApology() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(messages -> {
            throw new ToolChat.UnavailableException("AI 模型暂时连不上（请求超时）");
        });

        AgentLoop.Reply reply = run(model);

        assertThat(reply.stopReason()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(reply.degraded()).isTrue();
        assertThat(reply.reply()).contains("请求超时").contains("稍后再试");
        verify(runService).failRun(eq(42L), any());
    }

    @Test
    void stopsWhenTheTurnBudgetIsUsedUp() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(messages -> {
            clock.advance(Duration.ofSeconds(61));
            return calls("list_things");
        });

        AgentLoop.Reply reply = run(model);

        assertThat(reply.stopReason()).isEqualTo("TIME_LIMIT");
        assertThat(reply.reply()).contains("超过 60 秒");
        assertThat(model.seen).hasSize(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return self.withZone(other);
                }

                @Override
                public Instant instant() {
                    return self.now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
