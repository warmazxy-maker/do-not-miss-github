package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.agentkit.actions.PendingActionService;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.donotmiss.backend.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.donotmiss.backend.agentkit.AgentKitFixtures.MAPPER;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.NO_PARAMS;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.agent;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.json;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.readTool;
import static com.donotmiss.backend.agentkit.AgentKitFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutorTest {
    private static final ToolCaller STUDENT = new ToolCaller("student", "test", null);
    private final PendingActionService PENDING = AgentKitFixtures.pendingActions();

    private final List<AgentTool> tools = List.of(
            readTool("echo", (arguments, caller) -> ToolResult.ok(json("{\"user\":\"" + caller.userId() + "\"}"), "回显")),
            readTool("slow_a", (arguments, caller) -> {
                Thread.sleep(300);
                return ToolResult.ok(null, "a");
            }),
            readTool("slow_b", (arguments, caller) -> {
                Thread.sleep(300);
                return ToolResult.ok(null, "b");
            }),
            tool("hangs", ToolRisk.readOnly(), NO_PARAMS, Duration.ofMillis(200), null, (arguments, caller) -> {
                Thread.sleep(5_000);
                return ToolResult.ok(null, "late");
            }),
            readTool("explodes", (arguments, caller) -> {
                throw new IllegalStateException("boom");
            }),
            readTool("service_says_no", (arguments, caller) -> {
                throw ApiException.badRequest("开始日期晚于结束日期");
            }),
            tool("with_params", ToolRisk.readOnly(),
                    json("{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\",\"minimum\":1}},"
                            + "\"required\":[\"days\"],\"additionalProperties\":false}"),
                    Duration.ofSeconds(1), null, (arguments, caller) -> ToolResult.ok(arguments, "ok")),
            AgentKitFixtures.confirmTool("writes"),
            tool("limited", new ToolRisk(ToolRisk.DataEffect.READ, ToolRisk.ModelUsage.CALLS_MODEL), NO_PARAMS,
                    Duration.ofSeconds(1), RateLimitTier.AI_HEAVY, (arguments, caller) -> ToolResult.ok(null, "ok")),
            readTool("switched_off", (arguments, caller) -> ToolResult.ok(null, "ok")));

    private final ToolRegistry registry = new ToolRegistry(tools, List.of("switched_off"));
    private final ToolExecutor executor = new ToolExecutor(registry, AgentKitFixtures.students(),
            new RateLimiter(new AgentKitFixtures.CountingStore(), "Asia/Tokyo"), MAPPER, PENDING);
    private final AgentSpec agent = agent("tester",
            tools.stream().map(t -> t.definition().name()).toList(), 6);

    private ToolResult one(AgentSpec spec, String tool, String arguments, ToolCaller caller) {
        return executor.executeAll(spec, List.of(new ToolCallRequest("c1", tool, arguments, caller))).get(0).result();
    }

    private ToolResult one(String tool, String arguments) {
        return one(agent, tool, arguments, STUDENT);
    }

    @Test
    void runsAToolAsTheCallerTheSystemFilledIn() {
        ToolResult result = one("echo", "{}");
        assertThat(result.status()).isEqualTo(ToolResultStatus.OK);
        assertThat(result.data().path("user").asText()).isEqualTo("student");
    }

    @Test
    void refusesToolsOffTheAgentsListOrSwitchedOff() {
        AgentSpec narrow = agent("narrow", List.of("echo"), 6);
        assertThat(one(narrow, "with_params", "{\"days\":1}", STUDENT).status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(one("does_not_exist", "{}").status()).isEqualTo(ToolResultStatus.DENIED);
        ToolResult off = one("switched_off", "{}");
        assertThat(off.status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(off.summaryForUser()).contains("暂时关闭");
    }

    @Test
    void badArgumentsComeBackAsFixableErrors() {
        ToolResult notJson = one("with_params", "{days:");
        assertThat(notJson.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(notJson.messageForModel()).contains("JSON");

        ToolResult wrong = one("with_params", "{\"days\":0,\"userId\":\"other\"}");
        assertThat(wrong.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(wrong.messageForModel()).contains("days 不能小于 1").contains("不认识的参数 userId");

        assertThat(one("with_params", "{\"days\":3}").status()).isEqualTo(ToolResultStatus.OK);
    }

    @Test
    void checksThePermissionOfTheUserTheCallIsFor() {
        ToolResult result = one(agent, "echo", "{}", new ToolCaller("organiser", "test", null));
        assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(result.summaryForUser()).contains("没有权限");
    }

    @Test
    void confirmWritesBecomeCardsInsteadOfRunning() throws Exception {
        when(PENDING.propose(any(), any(), any())).thenReturn(ToolResult.pending(null, "card", "准备了确认卡片"));

        ToolResult result = one("writes", "{\"replaces_card\":3}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.PENDING_CONFIRMATION);
        verify(PENDING).propose(any(ConfirmableTool.class), eq(json("{\"replaces_card\":3}")), eq(STUDENT));
    }

    @Test
    void confirmWritesNeverRunInTheReadOnlyPlayground() throws Exception {
        ToolResult result = one(agent, "writes", "{}", new ToolCaller("student", "test", null, true));

        assertThat(result.status()).isEqualTo(ToolResultStatus.DENIED);
        verify(PENDING, never()).propose(any(), any(), any());
    }

    @Test
    void readOnlyRunsOnlyRunPlainReadTools() {
        AgentSpec spec = agent("ro", List.of("echo", "limited", "memo"), 6);
        ToolExecutor withMemo = new ToolExecutor(new ToolRegistry(List.of(tools.get(0), tools.get(8),
                tool("memo", new ToolRisk(ToolRisk.DataEffect.INTERNAL_WRITE, ToolRisk.ModelUsage.NONE), NO_PARAMS,
                        Duration.ofSeconds(1), null, (arguments, caller) -> ToolResult.ok(null, "wrote memo"))), List.of()),
                AgentKitFixtures.students(), new RateLimiter(new AgentKitFixtures.CountingStore(), "Asia/Tokyo"), MAPPER, PENDING);
        ToolCaller readOnly = new ToolCaller("student", "ro", null, true);
        List<ToolExecutor.Execution> results = withMemo.executeAll(spec, List.of(
                new ToolCallRequest("1", "echo", "{}", readOnly),
                new ToolCallRequest("2", "memo", "{}", readOnly),
                new ToolCallRequest("3", "limited", "{}", readOnly)));
        assertThat(results.get(0).result().status()).isEqualTo(ToolResultStatus.OK);
        assertThat(results.get(1).result().status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(results.get(2).result().status()).isEqualTo(ToolResultStatus.DENIED);
        assertThat(results.get(1).result().summaryForUser()).contains("调试台");
    }

    @Test
    void aTimedOutToolIsNotInterruptedAndFinishesOnItsOwn() throws Exception {
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        AgentTool slowPipeline = tool("slow_pipeline", ToolRisk.readOnly(), NO_PARAMS, Duration.ofMillis(100), null,
                (arguments, caller) -> {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ex) {
                        interrupted.set(true);
                        throw ex;
                    }
                    finished.countDown();
                    return ToolResult.ok(null, "late");
                });
        ToolExecutor single = new ToolExecutor(new ToolRegistry(List.of(slowPipeline), List.of()),
                AgentKitFixtures.students(), new RateLimiter(new AgentKitFixtures.CountingStore(), "Asia/Tokyo"), MAPPER, PENDING);

        ToolResult result = single.executeAll(agent("p", List.of("slow_pipeline"), 6),
                List.of(new ToolCallRequest("1", "slow_pipeline", "{}", STUDENT))).get(0).result();

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isFalse();
    }

    @Test
    void failuresBecomeHonestResults() {
        ToolResult timeout = one("hangs", "{}");
        assertThat(timeout.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(timeout.summaryForUser()).contains("没有结果");

        ToolResult exploded = one("explodes", "{}");
        assertThat(exploded.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(exploded.summaryForUser()).contains("出错了").contains("IllegalStateException");

        ToolResult refused = one("service_says_no", "{}");
        assertThat(refused.summaryForUser()).contains("开始日期晚于结束日期");
    }

    @Test
    void chargesTheToolsOwnRateLimitTier() {
        for (int i = 0; i < 3; i++) {
            assertThat(one("limited", "{}").status()).isEqualTo(ToolResultStatus.OK);
        }
        ToolResult fourth = one("limited", "{}");
        assertThat(fourth.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(fourth.summaryForUser()).contains("限流");
    }

    @Test
    void readOnlyCallsOfOneRoundRunInParallelAndKeepTheirOrder() {
        long started = System.nanoTime();
        List<ToolExecutor.Execution> results = executor.executeAll(agent, List.of(
                new ToolCallRequest("1", "slow_a", "{}", STUDENT),
                new ToolCallRequest("2", "does_not_exist", "{}", STUDENT),
                new ToolCallRequest("3", "slow_b", "{}", STUDENT)));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(results).extracting(e -> e.request().callId()).containsExactly("1", "2", "3");
        assertThat(results.get(0).result().summaryForUser()).isEqualTo("a");
        assertThat(results.get(2).result().summaryForUser()).isEqualTo("b");
        assertThat(millis).isLessThan(550);
    }
}
