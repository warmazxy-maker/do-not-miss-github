package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.AgentRegistry;
import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.agentkit.actions.ActionView;
import com.donotmiss.backend.agentkit.actions.PendingActionService;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.ai.ToolChat;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.master.goal.TandemGoalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Tandem (F1): one long conversation per student, with four layers of memory (F1 decision 2):
 * the stored messages, a working window (rolling summary + recent messages), long-term memories
 * written by Tandem itself, and the platform's preference profile (a tool).
 *
 * <p>Old tool data is not kept in the conversation (F1 decision 4): an earlier answer comes back
 * with a note of which tools it used and what they found, so Tandem knows it looked something up
 * and can look again for the full data.</p>
 */
@Service
public class TandemService {
    private static final Logger log = LoggerFactory.getLogger(TandemService.class);

    /** Messages always kept verbatim after the summary. */
    static final int RECENT_WINDOW = 12;
    /** Fold older messages once the unsummarized part is larger than this. */
    static final int COMPRESS_AFTER_MESSAGES = 24;
    static final int COMPRESS_AFTER_CHARS = 8000;
    static final int PAGE_SIZE = 30;

    private static final TypeReference<List<TandemDtos.Step>> STEPS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final TandemMessageRepository messages;
    private final TandemConversationRepository conversations;
    private final TandemMemoryRepository memories;
    private final AgentRegistry agentRegistry;
    private final AgentLoop agentLoop;
    private final AgentRunService runService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final PendingActionService pendingActions;
    private final TandemGoalService goals;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> compressing = ConcurrentHashMap.newKeySet();

    public TandemService(TandemMessageRepository messages, TandemConversationRepository conversations,
                         TandemMemoryRepository memories, AgentRegistry agentRegistry, AgentLoop agentLoop,
                         AgentRunService runService, OpenAiCompatibleLlmClient llmClient,
                         PendingActionService pendingActions, TandemGoalService goals, ObjectMapper objectMapper,
                         @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.messages = messages;
        this.conversations = conversations;
        this.memories = memories;
        this.agentRegistry = agentRegistry;
        this.agentLoop = agentLoop;
        this.runService = runService;
        this.llmClient = llmClient;
        this.pendingActions = pendingActions;
        this.goals = goals;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @PreDestroy
    void shutdown() {
        background.shutdownNow();
    }

    /** One student turn. Runs on a worker thread; progress goes to the listener as it happens. */
    public TandemDtos.Message turn(String userId, String text, AgentLoop.Listener listener) {
        AgentSpec agent = agentRegistry.find(MasterAgentSpec.NAME).orElseThrow();
        TandemMessageEntity question = messages.save(TandemMessageEntity.user(userId, text.trim()));
        List<ToolChat.Message> history = history(userId, question.getId());

        AgentLoop.Reply reply = agentLoop.run(agent, userId, userId, AgentRunType.MASTER_AGENT, history,
                question.getContent(), listener);

        List<TandemDtos.Step> steps = reply.toolCalls().stream()
                .map(trace -> new TandemDtos.Step(trace.toolName(), trace.title(), trace.detail(), trace.status(),
                        trace.summaryForUser()))
                .toList();
        TandemMessageEntity answer = messages.save(TandemMessageEntity.assistant(userId, reply.reply(),
                json(steps), json(reply.notices()), reply.degraded(), reply.runId()));
        compressLater(userId);
        return view(answer, cardsOf(userId, List.of(answer)));
    }

    /** What the model sees before the new message: memories, summary, then the recent messages. */
    List<ToolChat.Message> history(String userId, Long currentMessageId) {
        List<ToolChat.Message> history = new ArrayList<>();
        history.add(ToolChat.Message.system(memoryBlock(userId)));
        history.add(ToolChat.Message.system(goals.contextBlock(userId)));
        TandemConversationEntity conversation = conversations.findById(userId).orElse(null);
        Long after = conversation == null || conversation.getSummarizedUpTo() == null ? 0L : conversation.getSummarizedUpTo();
        if (conversation != null && conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            history.add(ToolChat.Message.system("【之前的对话摘要】\n" + conversation.getSummary()));
        }
        List<TandemMessageEntity> recent = messages.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, after).stream()
                .filter(message -> !message.getId().equals(currentMessageId))
                .toList();
        // If compression is behind, still only send a bounded window.
        if (recent.size() > COMPRESS_AFTER_MESSAGES) {
            recent = recent.subList(recent.size() - COMPRESS_AFTER_MESSAGES, recent.size());
        }
        Map<Long, List<ActionView>> cards = cardsOf(userId, recent);
        for (TandemMessageEntity message : recent) {
            if (message.isUser()) {
                history.add(ToolChat.Message.user(message.getContent()));
                continue;
            }
            String toolNote = toolNote(message);
            if (!toolNote.isEmpty()) {
                history.add(ToolChat.Message.system(toolNote));
            }
            String cardNote = cardNote(cards.getOrDefault(message.getRunId(), List.of()));
            if (!cardNote.isEmpty()) {
                history.add(ToolChat.Message.system(cardNote));
            }
            history.add(ToolChat.Message.assistant(message.getContent(), List.of()));
        }
        return history;
    }

    /** "（系统记录：下一条回答之前，你调用了 查日程（11/16～11/22）→ 0 个安排）" */
    private String toolNote(TandemMessageEntity message) {
        List<TandemDtos.Step> steps = read(message.getStepsJson(), STEPS);
        if (steps.isEmpty()) {
            return "";
        }
        String calls = steps.stream()
                .map(step -> step.title() + (step.detail() == null || step.detail().isBlank() ? "" : "（" + step.detail() + "）")
                        + " → " + step.summary())
                .collect(Collectors.joining("；"));
        return "（系统记录：下一条回答之前，你调用了 " + calls + "。原始数据没有保留，需要时重新查。）";
    }

    /**
     * F2: where each card of an earlier answer stands now, so Tandem never claims an unconfirmed
     * card was done, and knows which open card to replace.
     */
    private String cardNote(List<ActionView> cards) {
        if (cards.isEmpty()) {
            return "";
        }
        String list = cards.stream()
                .map(card -> "第 " + card.id() + " 号 " + card.card().kind() + "「" + card.card().headline() + "」——"
                        + card.statusLabel() + (card.message() == null || card.message().isBlank() ? "" : "（" + card.message() + "）"))
                .collect(Collectors.joining("；"));
        // PROBLEM_LOG 076: "已撤销" alone was read as still in effect; spell out what each state means.
        return "（系统记录：下一条回答里你提出了确认卡片，它们现在的状态：" + list + "。"
                + "已执行＝已经生效；已撤销＝学生撤回了，结果已经恢复原样，等于没做；"
                + "已取消、已过期、已被替换、执行失败＝没有执行。这条回答里描述的情况可能已经过时，以这里和工具查到的为准。）";
    }

    private Map<Long, List<ActionView>> cardsOf(String userId, List<TandemMessageEntity> shown) {
        List<Long> runIds = shown.stream()
                .filter(message -> !message.isUser())
                .map(TandemMessageEntity::getRunId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return pendingActions.viewsByRun(userId, runIds);
    }

    String memoryBlock(String userId) {
        LocalDate today = LocalDate.now(zone);
        List<TandemMemoryEntity> valid = memories.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(memory -> memory.validOn(today))
                .sorted(Comparator.comparing(TandemMemoryEntity::getCategory).thenComparing(TandemMemoryEntity::getId))
                .toList();
        if (valid.isEmpty()) {
            return "【长期记忆】\n（还没有。学生说出关键事实时，用 remember 记下来。）";
        }
        String lines = valid.stream()
                .map(memory -> "#" + memory.getId() + " [" + memory.getCategory().label() + "] " + memory.getContent()
                        + (memory.getValidUntil() == null ? "" : "（有效至 " + memory.getValidUntil() + "）"))
                .collect(Collectors.joining("\n"));
        return "【长期记忆】（共 " + valid.size() + " 条，上限 " + RememberTool.MAX_MEMORIES + " 条；编号用于更新或删除）\n" + lines;
    }

    // ---- working memory: fold older messages into the summary ----

    void compressLater(String userId) {
        if (!compressing.add(userId)) {
            return;
        }
        background.submit(() -> {
            try {
                compressIfNeeded(userId);
            } catch (RuntimeException ex) {
                log.warn("Tandem compression failed for a user; it will be retried after the next turn.", ex);
            } finally {
                compressing.remove(userId);
            }
        });
    }

    /** Returns true when something was folded. Leaves everything as is when the model fails. */
    boolean compressIfNeeded(String userId) {
        TandemConversationEntity conversation = conversations.findById(userId)
                .orElseGet(() -> TandemConversationEntity.empty(userId));
        Long after = conversation.getSummarizedUpTo() == null ? 0L : conversation.getSummarizedUpTo();
        List<TandemMessageEntity> pending = messages.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, after);
        int chars = pending.stream().mapToInt(message -> message.getContent().length()).sum();
        if (pending.size() <= COMPRESS_AFTER_MESSAGES && chars <= COMPRESS_AFTER_CHARS) {
            return false;
        }
        if (pending.size() <= RECENT_WINDOW) {
            return false;
        }
        List<TandemMessageEntity> fold = pending.subList(0, pending.size() - RECENT_WINDOW);
        Long runId = runService.startRun(userId, AgentRunType.MASTER_CONTEXT_COMPRESSION, "fold older Tandem messages",
                "messages=" + fold.size());
        Long stepId = runService.startStep(runId, AgentStepName.GUIDE_CONTEXT_COMPRESSION, "folding " + fold.size() + " messages");
        String summary = llmClient.chatPlain(compressionPrompt(conversation.getSummary(), fold)).orElse(null);
        if (summary == null || summary.isBlank()) {
            runService.failStep(stepId, new IllegalStateException("model returned nothing"));
            runService.failRun(runId, new IllegalStateException("summary unavailable; messages kept verbatim"));
            return false;
        }
        conversation.fold(summary.trim(), fold.get(fold.size() - 1).getId());
        conversations.save(conversation);
        runService.completeStep(stepId, "summary chars=" + summary.length());
        runService.finishRun(runId, summary);
        return true;
    }

    private String compressionPrompt(String previousSummary, List<TandemMessageEntity> fold) {
        String transcript = fold.stream()
                .map(message -> (message.isUser() ? "学生：" : "你：") + message.getContent())
                .collect(Collectors.joining("\n"));
        return """
                你是学生成长平台上的伙伴 Tandem。下面是你和学生较早的对话，请压缩成一段摘要，供你以后继续聊天时参考。
                保留：学生提过的问题、需求和想法；你给过的建议和结论；还没完成的事、说好要做的事。
                不要保留能用工具重新查到的数据细节（活动列表、日程、分数），只写结论。
                学生亲口说的关键事实已经在长期记忆里，不必重复。用中文，不超过 600 字，用第二人称“学生”。

                【已有摘要】
                %s

                【要压缩的对话】
                %s
                """.formatted(previousSummary == null || previousSummary.isBlank() ? "（无）" : previousSummary, transcript);
    }

    // ---- page reads and the student's own controls ----

    @Transactional(readOnly = true)
    public TandemDtos.MessagePage page(String userId, Long beforeId) {
        PageRequest page = PageRequest.of(0, PAGE_SIZE + 1);
        List<TandemMessageEntity> newestFirst = beforeId == null
                ? messages.findByUserIdOrderByIdDesc(userId, page)
                : messages.findByUserIdAndIdLessThanOrderByIdDesc(userId, beforeId, page);
        boolean hasMore = newestFirst.size() > PAGE_SIZE;
        List<TandemMessageEntity> shown = new ArrayList<>(newestFirst.subList(0, Math.min(PAGE_SIZE, newestFirst.size())));
        Collections.reverse(shown);
        Map<Long, List<ActionView>> cards = cardsOf(userId, shown);
        return new TandemDtos.MessagePage(shown.stream().map(message -> view(message, cards)).toList(), hasMore);
    }

    /**
     * Clears the conversation and its summary; long-term memories stay (F1 decision 9). Open cards
     * are voided (F2 decision 6); what was already done stays done and can be changed on its page.
     */
    @Transactional
    public void clear(String userId) {
        pendingActions.expireOpen(userId, MasterAgentSpec.NAME);
        messages.deleteAllOfUser(userId);
        conversations.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public TandemDtos.Memories memories(String userId) {
        LocalDate today = LocalDate.now(zone);
        List<TandemDtos.Memory> list = memories.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(memory -> new TandemDtos.Memory(memory.getId(), memory.getCategory().name(),
                        memory.getCategory().label(), memory.getContent(), memory.getSourceQuote(),
                        memory.getValidUntil(), !memory.validOn(today), memory.getUpdatedAt()))
                .toList();
        return new TandemDtos.Memories(list, RememberTool.MAX_MEMORIES);
    }

    @Transactional
    public void deleteMemory(String userId, Long memoryId) {
        TandemMemoryEntity memory = memories.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> ApiException.notFound("这条记忆不存在"));
        memories.delete(memory);
    }

    TandemDtos.Message view(TandemMessageEntity message, Map<Long, List<ActionView>> cards) {
        List<ActionView> own = message.isUser() || message.getRunId() == null
                ? List.of() : cards.getOrDefault(message.getRunId(), List.of());
        return new TandemDtos.Message(message.getId(), message.getRole(), message.getContent(),
                read(message.getStepsJson(), STEPS), read(message.getNoticesJson(), STRINGS),
                message.isDegraded(), message.getCreatedAt(), own);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            log.warn("Unreadable Tandem message JSON ignored.", ex);
            return List.of();
        }
    }
}
