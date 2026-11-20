package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.agentkit.actions.PendingActionService;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.ratelimit.RateLimitedException;
import com.donotmiss.backend.ratelimit.RateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the tool calls of one model round. Before a tool runs, Java checks structure only
 * (F0 decision 5): the tool exists, is on the agent's list and switched on, the arguments fit the
 * schema, the user has the permission, the rate limit allows it. Which tool and what the arguments
 * mean is the model's call.
 *
 * <p>Nothing here throws: every failure becomes a result the model can read and the student is
 * told about (F0 decision 6). Read-only calls of one round run in parallel; anything else runs
 * one after another, in the order the model asked.</p>
 */
@Component
public class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolPermissions permissions;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final PendingActionService pendingActions;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutor(ToolRegistry registry, ToolPermissions permissions, RateLimiter rateLimiter,
                        ObjectMapper objectMapper, PendingActionService pendingActions) {
        this.registry = registry;
        this.permissions = permissions;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.pendingActions = pendingActions;
    }

    /** One finished call, with what is needed to record and display it. */
    public record Execution(ToolCallRequest request, ToolDefinition definition, JsonNode arguments,
                            ToolResult result, long durationMillis) {
        public String title() {
            return definition == null ? request.toolName() : definition.title();
        }
    }

    public List<Execution> executeAll(AgentSpec agent, List<ToolCallRequest> calls) {
        List<Execution> results = new ArrayList<>(calls.size());
        List<Prepared> parallel = new ArrayList<>();
        List<Integer> parallelSlots = new ArrayList<>();
        for (ToolCallRequest call : calls) {
            Prepared prepared = prepare(agent, call);
            if (prepared.immediate != null) {
                results.add(prepared.immediate);
            } else if (prepared.definition.risk().dataEffect() == ToolRisk.DataEffect.READ) {
                results.add(null);
                parallel.add(prepared);
                parallelSlots.add(results.size() - 1);
            } else {
                results.add(run(prepared));
            }
        }
        if (parallel.size() == 1) {
            results.set(parallelSlots.get(0), run(parallel.get(0)));
        } else if (!parallel.isEmpty()) {
            // Each run() is bounded by its own timeout, so joining them cannot hang.
            List<Future<Execution>> futures = parallel.stream().map(p -> workers.submit(() -> run(p))).toList();
            for (int i = 0; i < futures.size(); i++) {
                results.set(parallelSlots.get(i), join(futures.get(i), parallel.get(i)));
            }
        }
        return results;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    private Execution join(Future<Execution> future, Prepared prepared) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return finished(prepared, ToolResult.error("被中断了", prepared.definition.title() + "被中断了"), 0);
        } catch (ExecutionException ex) {
            return finished(prepared, unexpected(prepared.definition, ex.getCause()), 0);
        }
    }

    private Prepared prepare(AgentSpec agent, ToolCallRequest call) {
        String name = call.toolName();
        if (!agent.toolNames().contains(name) || !registry.contains(name)) {
            return Prepared.immediate(new Execution(call, null, null, ToolResult.denied(
                    "没有名为 " + name + " 的工具，或你不能使用它。只能使用工具列表里的工具。",
                    "想用一个不存在的工具「" + name + "」，已拒绝"), 0));
        }
        AgentTool tool = registry.find(name).orElseThrow();
        ToolDefinition definition = tool.definition();
        if (!registry.isEnabled(name)) {
            return Prepared.immediate(new Execution(call, definition, null, ToolResult.denied(
                    "工具 " + name + " 暂时被关闭了，告诉学生这项查询暂时用不了。",
                    "「" + definition.title() + "」暂时关闭了"), 0));
        }
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(call.argumentsJson() == null || call.argumentsJson().isBlank()
                    ? "{}" : call.argumentsJson());
        } catch (JsonProcessingException ex) {
            return Prepared.immediate(new Execution(call, definition, null, ToolResult.error(
                    "参数不是合法的 JSON，请按参数说明重新调用。",
                    "「" + definition.title() + "」的参数格式不对"), 0));
        }
        List<String> problems = ToolSchemas.validate(definition.parameters(), arguments);
        if (!problems.isEmpty()) {
            return Prepared.immediate(new Execution(call, definition, arguments, ToolResult.error(
                    "参数不对：" + String.join("；", problems) + "。改正后可以再调用一次。",
                    "「" + definition.title() + "」的参数不对：" + String.join("；", problems)), 0));
        }
        if (!permissions.of(call.caller().userId()).contains(definition.permission())) {
            return Prepared.immediate(new Execution(call, definition, arguments, ToolResult.denied(
                    "这个用户没有使用 " + name + " 的权限。告诉学生这项查询对他不可用。",
                    "没有权限使用「" + definition.title() + "」"), 0));
        }
        // Read-only runs (the ops playground) must leave no trace on the user: no writes, and no
        // model-calling tools, which record runs and recommendation impressions under the user.
        if (call.caller().readOnly() && (definition.risk().dataEffect() != ToolRisk.DataEffect.READ
                || definition.risk().modelUsage() == ToolRisk.ModelUsage.CALLS_MODEL)) {
            return Prepared.immediate(new Execution(call, definition, arguments, ToolResult.denied(
                    "这次运行是只读的调试，这个工具会留下记录，所以没有执行。继续用已有信息回答。",
                    "「" + definition.title() + "」在调试台里不执行"), 0));
        }
        if (definition.rateLimitTier() != null) {
            try {
                rateLimiter.consume(definition.rateLimitTier(), call.caller().userId());
            } catch (RateLimitedException ex) {
                return Prepared.immediate(new Execution(call, definition, arguments, ToolResult.error(
                        "被限流了：" + ex.getMessage() + "。告诉学生稍后再试。",
                        "「" + definition.title() + "」用得太频繁，被限流了"), 0));
            }
        }
        return new Prepared(call, tool, definition, arguments, null);
    }

    private Execution run(Prepared prepared) {
        long started = System.nanoTime();
        ToolDefinition definition = prepared.definition;
        ToolResult result;
        {
            // Not closed per call: closing would wait for a timed-out tool that ignores the interrupt.
            // F2: a write tool never runs here; the call becomes a confirmation card instead.
            Future<ToolResult> future = workers.submit(() -> prepared.tool instanceof ConfirmableTool confirmable
                    ? pendingActions.propose(confirmable, prepared.arguments, prepared.request.caller())
                    : prepared.tool.execute(prepared.arguments, prepared.request.caller()));
            try {
                result = future.get(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
                if (result == null) {
                    result = ToolResult.error("工具没有返回结果。", "「" + definition.title() + "」没有返回结果");
                }
            } catch (TimeoutException ex) {
                // Stop waiting, but do not interrupt: a tool that wraps a pipeline (search_events) still
                // writes its own run records, and an interrupted thread cannot get a database
                // connection, which left a recommendation run stuck as RUNNING (PROBLEM_LOG 074).
                // The tool finishes on its virtual thread and its result is dropped.
                future.cancel(false);
                long seconds = definition.timeout().toSeconds();
                result = ToolResult.error("超过 " + seconds + " 秒没有结果，这次没查到。如实告诉学生。",
                        "「" + definition.title() + "」超过 " + seconds + " 秒没有结果");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                result = ToolResult.error("被中断了。", "「" + definition.title() + "」被中断了");
            } catch (ExecutionException ex) {
                result = unexpected(definition, ex.getCause());
            }
        }
        return finished(prepared, result, (System.nanoTime() - started) / 1_000_000);
    }

    private ToolResult unexpected(ToolDefinition definition, Throwable cause) {
        if (cause instanceof ApiException api) {
            // Services already speak plain Chinese to users; pass it on.
            return ToolResult.error("没查成：" + api.getMessage(), "「" + definition.title() + "」没查成：" + api.getMessage());
        }
        log.warn("Agent tool {} failed.", definition.name(), cause);
        String kind = cause == null ? "未知错误" : cause.getClass().getSimpleName();
        return ToolResult.error("系统内部出错了（" + kind + "），这次没查到。如实告诉学生。",
                "「" + definition.title() + "」出错了（" + kind + "）");
    }

    private Execution finished(Prepared prepared, ToolResult result, long millis) {
        return new Execution(prepared.request, prepared.definition, prepared.arguments, result, millis);
    }

    private record Prepared(ToolCallRequest request, AgentTool tool, ToolDefinition definition,
                            JsonNode arguments, Execution immediate) {
        static Prepared immediate(Execution execution) {
            return new Prepared(execution.request(), null, execution.definition(), execution.arguments(), execution);
        }
    }
}
