package com.donotmiss.backend.guide;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.memory.UserMemoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GuideConversationService {
    private static final int COMPRESS_MESSAGE_THRESHOLD = 14;
    private static final int COMPRESS_CHAR_THRESHOLD = 2400;
    private static final int KEEP_RECENT_MESSAGES = 6;

    private final OpenAiCompatibleLlmClient llmClient;
    private final UserMemoryService userMemoryService;
    private final EventService eventService;
    private final GuideSessionService guideSessionService;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;

    public GuideConversationService(OpenAiCompatibleLlmClient llmClient,
                                    UserMemoryService userMemoryService,
                                    EventService eventService,
                                    GuideSessionService guideSessionService,
                                    AgentRunService agentRunService,
                                    AgentTraceArtifactService traceArtifactService) {
        this.llmClient = llmClient;
        this.userMemoryService = userMemoryService;
        this.eventService = eventService;
        this.guideSessionService = guideSessionService;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
    }

    public String replyWithoutRag(String userId,
                                  String message,
                                  GuideDtos.GuideDecision decision,
                                  GuideDtos.GuideSessionView session) {
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.GUIDE_CONVERSATION,
                message,
                "action=" + (decision == null ? "" : decision.action()) + ", sessionId=" + safe(session == null ? "" : session.sessionId())
        );
        Long stepId = null;
        try {
            stepId = agentRunService.startStep(
                    runId,
                    AgentStepName.GUIDE_CHAT_REPLY,
                    "Generate non-RAG reply."
            );
            String fallback = fallbackReply(decision);
            String reply = llmClient.isEnabled()
                    ? llmClient.chatPlain(nonRagPrompt(userId, message, decision, session))
                    .map(text -> compact(text, 900))
                    .orElse(fallback)
                    : fallback;
            traceArtifactService.record(
                    runId,
                    AgentStepName.GUIDE_CHAT_REPLY,
                    "GUIDE_NON_RAG_REPLY",
                    java.util.Map.of(
                            "message", safe(message),
                            "decision", decision == null ? "" : decision,
                            "sessionId", safe(session == null ? "" : session.sessionId()),
                            "reply", reply
                    ),
                    "replyLength=" + reply.length()
            );
            agentRunService.completeStep(stepId, "replyLength=" + reply.length());
            agentRunService.finishRun(runId, compact(reply, 1000));
            return reply;
        } catch (RuntimeException | Error ex) {
            if (stepId != null) {
                agentRunService.failStep(stepId, ex);
            }
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    public GuideSessionEntity compressIfNeeded(String userId, GuideSessionEntity session) {
        List<GuideDtos.GuideMessage> messages = guideSessionService.recentMessages(session);
        if (!shouldCompress(messages)) {
            return session;
        }
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.GUIDE_CONTEXT_COMPRESSION,
                session.getCurrentGoal(),
                "sessionId=" + session.getSessionId() + ", messageCount=" + messages.size()
        );
        Long stepId = null;
        try {
            stepId = agentRunService.startStep(
                    runId,
                    AgentStepName.GUIDE_CONTEXT_COMPRESSION,
                    "Compress guide session context."
            );
            int keepFrom = Math.max(0, messages.size() - KEEP_RECENT_MESSAGES);
            List<GuideDtos.GuideMessage> olderMessages = messages.subList(0, keepFrom);
            List<GuideDtos.GuideMessage> messagesToKeep = new ArrayList<>(messages.subList(keepFrom, messages.size()));

            String summary = llmClient.chatPlain(compressionPrompt(userId, session, olderMessages))
                    .map(text -> compact(text, 3000))
                    .orElseGet(() -> localCompression(session.getSessionSummary(), olderMessages));
            GuideSessionEntity compressed = guideSessionService.compressContext(session, summary, messagesToKeep);
            traceArtifactService.record(
                    runId,
                    AgentStepName.GUIDE_CONTEXT_COMPRESSION,
                    "GUIDE_CONTEXT_SUMMARY",
                    java.util.Map.of(
                            "sessionId", session.getSessionId(),
                            "compressedMessageCount", olderMessages.size(),
                            "keptMessageCount", messagesToKeep.size(),
                            "summary", summary
                    ),
                    "compressed=" + olderMessages.size() + ", kept=" + messagesToKeep.size()
            );
            agentRunService.completeStep(stepId, "compressed=" + olderMessages.size() + ", kept=" + messagesToKeep.size());
            agentRunService.finishRun(runId, compact(summary, 1000));
            return compressed;
        } catch (RuntimeException | Error ex) {
            if (stepId != null) {
                agentRunService.failStep(stepId, ex);
            }
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private String nonRagPrompt(String userId,
                                String message,
                                GuideDtos.GuideDecision decision,
                                GuideDtos.GuideSessionView session) {
        return """
                你是 do not miss 的“引导者”Agent，正在和学生围绕社会实践、挑战、学习路径和已有推荐结果聊天。
                这次动作判断为 %s，不允许调用 RAG，也不要编造新的活动。
                如果用户在问已有推荐，基于会话摘要、最近消息和最近推荐活动回答。
                如果信息不足，就自然地追问一个最关键的问题。
                回复要求：中文，简洁，像产品里的助手，不要输出 JSON，不要说自己不能访问数据库。

                当前用户输入：
                %s

                动作判断：
                reason=%s
                assistantHint=%s

                会话摘要：
                %s

                当前目标：
                %s

                最近消息：
                %s

                最近推荐活动：
                %s

                学生画像弱参考：
                %s
                """.formatted(
                decision == null ? "" : decision.action(),
                safe(message),
                decision == null ? "" : safe(decision.reason()),
                decision == null ? "" : safe(decision.assistantHint()),
                session == null ? "" : safe(session.sessionSummary()),
                session == null ? "" : safe(session.currentGoal()),
                session == null ? List.of() : session.recentMessages(),
                recentRecommendedEventsText(session),
                userMemoryService.promptText(userMemoryService.profile(userId))
        );
    }

    private String compressionPrompt(String userId,
                                     GuideSessionEntity session,
                                     List<GuideDtos.GuideMessage> olderMessages) {
        return """
                你是 do not miss 的会话记忆压缩器。
                请把旧对话压缩成结构化中文摘要，供后续推荐对话继续使用。
                必须保留：
                - 用户当前目标
                - 用户已经接受的偏好
                - 用户明确否定的条件
                - 已经解释过或比较过的推荐结果
                - 尚未解决的问题
                不要新增事实，不要编造活动。
                输出 300 字以内的纯文本摘要。

                原有摘要：
                %s

                当前目标：
                %s

                需要压缩的旧消息：
                %s

                学生画像弱参考：
                %s
                """.formatted(
                safe(session.getSessionSummary()),
                safe(session.getCurrentGoal()),
                olderMessages,
                userMemoryService.promptText(userMemoryService.profile(userId))
        );
    }

    private String recentRecommendedEventsText(GuideDtos.GuideSessionView session) {
        if (session == null || session.lastRecommendedEventIds() == null || session.lastRecommendedEventIds().isEmpty()) {
            return "[]";
        }
        List<EventDtos.EventResponse> events = new ArrayList<>();
        for (Long eventId : session.lastRecommendedEventIds()) {
            if (eventId == null) {
                continue;
            }
            try {
                EventEntity entity = eventService.getEntity(eventId);
                events.add(EventDtos.EventResponse.from(entity));
            } catch (RuntimeException ignored) {
                // 推荐快照里可能存在已删除活动；聊天时跳过即可。
            }
            if (events.size() >= 8) {
                break;
            }
        }
        return events.toString();
    }

    private boolean shouldCompress(List<GuideDtos.GuideMessage> messages) {
        if (messages == null || messages.size() <= KEEP_RECENT_MESSAGES) {
            return false;
        }
        int chars = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return messages.size() >= COMPRESS_MESSAGE_THRESHOLD || chars >= COMPRESS_CHAR_THRESHOLD;
    }

    private String localCompression(String previousSummary, List<GuideDtos.GuideMessage> olderMessages) {
        StringBuilder builder = new StringBuilder();
        if (hasText(previousSummary)) {
            builder.append(previousSummary.trim()).append("\n");
        }
        builder.append("旧对话摘要：");
        for (GuideDtos.GuideMessage message : olderMessages) {
            builder.append(message.role()).append(":")
                    .append(compact(message.content(), 80))
                    .append("；");
        }
        return compact(builder.toString(), 3000);
    }

    private String fallbackReply(GuideDtos.GuideDecision decision) {
        if (decision == null) {
            return "我需要再确认一下你的需求。";
        }
        if (hasText(decision.assistantHint())) {
            return decision.assistantHint();
        }
        return switch (decision.action()) {
            case ASK_CLARIFY -> "你可以补充想提升的方向、地点、线上线下或难度偏好。";
            case CHAT_ONLY -> "我会基于上一轮推荐继续聊，不重新检索。";
            case CALL_RAG_NEW, CALL_RAG_REFINE -> "我已经理解你的需求。";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String compact(String value, int maxLength) {
        String text = safe(value);
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
