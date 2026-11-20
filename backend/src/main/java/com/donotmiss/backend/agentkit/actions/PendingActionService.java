package com.donotmiss.backend.agentkit.actions;

import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolPermissions;
import com.donotmiss.backend.agentkit.ToolRegistry;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Confirmation cards (F2). A write tool called by the model ends here as a PENDING row; the
 * student's click confirms, cancels or later rolls it back.
 *
 * <p>Three guarantees, all enforced here and not left to the prompt:</p>
 * <ul>
 *   <li>What the card shows is what runs: the click runs the frozen payload, no model involved.</li>
 *   <li>One click wins: every state change is a compare-and-set on the status, and confirming
 *       runs the change in the same transaction as the status change, so a failure rolls both back
 *       and a row is never left half-done.</li>
 *   <li>A rollback reverts only this action, and only while what it touched is unchanged.</li>
 * </ul>
 */
@Service
public class PendingActionService {
    private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

    private final PendingActionRepository repository;
    private final ToolRegistry registry;
    private final ToolPermissions permissions;
    private final AgentTraceArtifactService artifacts;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public PendingActionService(PendingActionRepository repository, ToolRegistry registry, ToolPermissions permissions,
                                AgentTraceArtifactService artifacts, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                @Value("${app.agent.action-ttl-hours:24}") long ttlHours) {
        this(repository, registry, permissions, artifacts, objectMapper, new TransactionTemplate(transactionManager),
                Duration.ofHours(ttlHours), Clock.systemUTC());
    }

    PendingActionService(PendingActionRepository repository, ToolRegistry registry, ToolPermissions permissions,
                         AgentTraceArtifactService artifacts, ObjectMapper objectMapper,
                         TransactionTemplate transactions, Duration ttl, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.permissions = permissions;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** What a click returns: the card as it now stands, and one sentence about this click. */
    public record Outcome(ActionView action, boolean ok, String message) {
    }

    // ---- proposing (called by the tool executor instead of running the tool) ----

    public ToolResult propose(ConfirmableTool tool, JsonNode arguments, ToolCaller caller) throws Exception {
        ToolDefinition definition = tool.definition();
        ConfirmableTool.Proposal proposal;
        try {
            proposal = tool.propose(arguments, caller);
        } catch (ApiException ex) {
            // First check failed (F2 decision 5): no card, the model hears why.
            return ToolResult.error("这件事现在做不了：" + ex.getMessage() + "。如实告诉学生原因，不要生成卡片。",
                    "「" + definition.title() + "」做不了：" + ex.getMessage());
        }
        Instant now = clock.instant();
        PendingActionEntity saved = repository.save(PendingActionEntity.proposed(caller.userId(), caller.agentName(),
                caller.runId(), definition.name(), definition.version(), json(proposal.payload()),
                json(proposal.card()), now, now.plus(ttl)));

        String replaceNote = "";
        JsonNode replaces = arguments.path(ConfirmableTool.REPLACES_ARGUMENT);
        if (replaces.isIntegralNumber()) {
            Integer replaced = transactions.execute(status -> repository.supersede(replaces.asLong(), saved.getId(),
                    caller.userId(), now, PendingActionStatus.PENDING, PendingActionStatus.SUPERSEDED));
            replaceNote = replaced != null && replaced == 1
                    ? "第 " + replaces.asLong() + " 号卡片已经作废，由这张代替。"
                    : "第 " + replaces.asLong() + " 号卡片已经不是等待确认的状态，没有被替换。";
        }

        ActionCard card = proposal.card();
        record(saved, "proposed", Map.of("arguments", arguments, "card", card), "提出：" + card.kind() + "「" + card.headline() + "」");

        ObjectNode data = objectMapper.createObjectNode();
        data.put("card_id", saved.getId());
        data.put("status", "等待学生确认");
        data.put("kind", card.kind());
        data.put("headline", card.headline());
        ArrayNode fields = data.putArray("fields");
        card.fields().forEach(field -> fields.addObject().put(field.label(), field.value()));
        if (!card.warnings().isEmpty()) {
            ArrayNode warnings = data.putArray("warnings");
            card.warnings().forEach(warnings::add);
        }
        String note = "已经生成第 " + saved.getId() + " 号确认卡片，显示在你这条回答下面。现在还没有执行：学生点“确认”才会执行，"
                + ttl.toHours() + " 小时内有效。回答时请学生看卡片确认，不要说已经做好了。"
                + (card.warnings().isEmpty() ? "" : "卡片上有提醒，回答时提一下。")
                + replaceNote;
        return ToolResult.pending(data, note, "准备了确认卡片：" + card.kind() + "「" + card.headline() + "」");
    }

    // ---- the student's clicks ----

    public Outcome confirm(String userId, Long id) {
        PendingActionEntity action = load(userId, id);
        Instant now = clock.instant();
        if (action.overdue(now)) {
            return expire(action, now);
        }
        if (action.getStatus() != PendingActionStatus.PENDING) {
            return new Outcome(view(action, now), false, "这张卡片已经处理过了（" + action.getStatus().label() + "），没有再执行。");
        }
        AgentTool found = registry.find(action.getToolName()).orElse(null);
        String blocked = null;
        if (!(found instanceof ConfirmableTool) || !registry.isEnabled(action.getToolName())) {
            blocked = "这项操作暂时关闭了";
        } else if (!permissions.of(userId).contains(found.definition().permission())) {
            blocked = "你现在没有做这件事的权限";
        }
        if (blocked != null) {
            return fail(action, blocked, now);
        }
        ConfirmableTool tool = (ConfirmableTool) found;
        try {
            PendingActionEntity done = transactions.execute(status -> {
                if (repository.transition(id, userId, PendingActionStatus.PENDING, PendingActionStatus.DONE, now) == 0) {
                    return null;
                }
                ConfirmableTool.Applied applied = call(() -> tool.apply(read(action.getPayloadJson()), userId));
                PendingActionEntity fresh = repository.findById(id).orElseThrow();
                fresh.setResultMessage(applied.message());
                fresh.setUndoJson(json(applied.undoData()));
                return repository.save(fresh);
            });
            if (done == null) {
                // Another click got there first; report what it did.
                PendingActionEntity current = load(userId, id);
                return new Outcome(view(current, now), false, "这张卡片已经处理过了（" + current.getStatus().label() + "）。");
            }
            record(done, "confirmed", Map.of("undo", read(done.getUndoJson())), "确认并执行：" + done.getResultMessage());
            return new Outcome(view(done, now), true, done.getResultMessage());
        } catch (RuntimeException ex) {
            return fail(action, reason(ex), now);
        }
    }

    public Outcome cancel(String userId, Long id) {
        PendingActionEntity action = load(userId, id);
        Instant now = clock.instant();
        if (action.overdue(now)) {
            return expire(action, now);
        }
        Integer changed = transactions.execute(status ->
                repository.transition(id, userId, PendingActionStatus.PENDING, PendingActionStatus.CANCELLED, now));
        PendingActionEntity current = load(userId, id);
        if (changed == null || changed == 0) {
            return new Outcome(view(current, now), false, "这张卡片已经处理过了（" + current.getStatus().label() + "）。");
        }
        record(current, "cancelled", Map.of(), "学生取消");
        return new Outcome(view(current, now), true, "已取消，什么都没有改。");
    }

    /** F2 decision 7: roll a confirmed action back, if what it touched is still as it left it. */
    public Outcome undo(String userId, Long id) {
        PendingActionEntity action = load(userId, id);
        Instant now = clock.instant();
        if (action.getStatus() != PendingActionStatus.DONE) {
            return new Outcome(view(action, now), false, "只有已执行的操作可以撤销，这张卡片现在是“" + action.getStatus().label() + "”。");
        }
        // A rollback is a safety net, so it works even if the tool was switched off since.
        AgentTool found = registry.find(action.getToolName()).orElse(null);
        if (!(found instanceof ConfirmableTool tool)) {
            return new Outcome(view(action, now), false, "这项操作已经不存在了，请到对应页面手动处理。");
        }
        try {
            String message = transactions.execute(status -> {
                if (repository.markUndone(id, userId, now, PendingActionStatus.DONE, PendingActionStatus.UNDONE) == 0) {
                    return null;
                }
                String result = call(() -> tool.undo(read(action.getPayloadJson()), read(action.getUndoJson()), userId));
                PendingActionEntity fresh = repository.findById(id).orElseThrow();
                fresh.setResultMessage(result);
                repository.save(fresh);
                return result;
            });
            PendingActionEntity current = load(userId, id);
            if (message == null) {
                return new Outcome(view(current, now), false, "这张卡片已经处理过了（" + current.getStatus().label() + "）。");
            }
            record(current, "undone", Map.of(), "撤销：" + message);
            return new Outcome(view(current, now), true, message);
        } catch (RuntimeException ex) {
            // Nothing changed: the transaction rolled the status back to DONE.
            String reason = reason(ex);
            record(action, "undo_refused", Map.of("reason", reason), "撤销没有成功：" + reason);
            return new Outcome(view(load(userId, id), now), false, "没能撤销：" + reason);
        }
    }

    // ---- reads ----

    /** Cards of the given runs, by run id, for showing under the answers. */
    public Map<Long, List<ActionView>> viewsByRun(String userId, Collection<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        return repository.findByUserIdAndRunIdInOrderByIdAsc(userId, runIds).stream()
                .collect(Collectors.groupingBy(PendingActionEntity::getRunId, LinkedHashMap::new,
                        Collectors.mapping(action -> view(action, now), Collectors.toList())));
    }

    /** Clearing a conversation voids its open cards (F2 decision 6). */
    public void expireOpen(String userId, String agentName) {
        transactions.executeWithoutResult(status -> repository.expireOpen(userId, agentName, clock.instant(),
                PendingActionStatus.PENDING, PendingActionStatus.EXPIRED));
    }

    public ActionView view(PendingActionEntity action, Instant now) {
        PendingActionStatus status = action.overdue(now) ? PendingActionStatus.EXPIRED : action.getStatus();
        ActionCard card;
        try {
            card = objectMapper.readValue(action.getCardJson(), ActionCard.class);
        } catch (JsonProcessingException ex) {
            log.warn("Unreadable action card {}.", action.getId(), ex);
            card = new ActionCard("操作", "（卡片内容读不出来）", List.of(), List.of(), List.of(), null);
        }
        return new ActionView(action.getId(), action.getToolName(), card, status.name(), status.label(),
                action.getResultMessage(), action.getExpiresAt(),
                status == PendingActionStatus.PENDING, status == PendingActionStatus.DONE);
    }

    // ---- helpers ----

    private PendingActionEntity load(String userId, Long id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("这张卡片不存在"));
    }

    private Outcome expire(PendingActionEntity action, Instant now) {
        transactions.execute(status -> repository.transition(action.getId(), action.getUserId(),
                PendingActionStatus.PENDING, PendingActionStatus.EXPIRED, now));
        PendingActionEntity current = load(action.getUserId(), action.getId());
        record(current, "expired", Map.of(), "过期");
        return new Outcome(view(current, now), false, "这张卡片已经过期了，可以让 Tandem 重新准备一张。");
    }

    private Outcome fail(PendingActionEntity action, String reason, Instant now) {
        transactions.executeWithoutResult(status -> {
            if (repository.transition(action.getId(), action.getUserId(), PendingActionStatus.PENDING,
                    PendingActionStatus.FAILED, now) == 1) {
                PendingActionEntity fresh = repository.findById(action.getId()).orElseThrow();
                fresh.setResultMessage(reason);
                repository.save(fresh);
            }
        });
        PendingActionEntity current = load(action.getUserId(), action.getId());
        record(current, "failed", Map.of("reason", reason), "执行失败：" + reason);
        return new Outcome(view(current, now), false, "没有执行成功：" + reason);
    }

    private void record(PendingActionEntity action, String event, Map<String, Object> extra, String summary) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("actionId", action.getId());
        content.put("tool", action.getToolName());
        content.put("toolVersion", action.getToolVersion());
        content.put("event", event);
        content.put("status", action.getStatus().name());
        content.putAll(extra);
        artifacts.record(action.getRunId(), AgentStepName.AGENT_ACTION, "action:" + event, content, summary);
    }

    /** Services speak plain Chinese in ApiException; anything else is reported by kind only. */
    private String reason(Throwable ex) {
        Throwable cause = ex instanceof ActionFailure && ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ApiException api) {
            return api.getMessage();
        }
        log.warn("Confirmed action failed unexpectedly.", cause);
        return "系统出错了（" + cause.getClass().getSimpleName() + "）";
    }

    private interface Step<T> {
        T run() throws Exception;
    }

    private static <T> T call(Step<T> step) {
        try {
            return step.run();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ActionFailure(ex);
        }
    }

    private static final class ActionFailure extends RuntimeException {
        ActionFailure(Throwable cause) {
            super(cause);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode read(String json) {
        try {
            return json == null ? objectMapper.nullNode() : objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
