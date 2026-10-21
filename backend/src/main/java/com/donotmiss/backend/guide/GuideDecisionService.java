package com.donotmiss.backend.guide;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GuideDecisionService {
    private final OpenAiCompatibleLlmClient llmClient;
    private final UserMemoryService userMemoryService;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;

    public GuideDecisionService(OpenAiCompatibleLlmClient llmClient,
                                UserMemoryService userMemoryService,
                                AgentRunService agentRunService,
                                AgentTraceArtifactService traceArtifactService) {
        this.llmClient = llmClient;
        this.userMemoryService = userMemoryService;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
    }

    public GuideDtos.GuideDecision decide(String userId,
                                          String message,
                                          GuideDtos.GuideSessionView session) {
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.GUIDE_ACTION_DECISION,
                message,
                "sessionId=" + safe(session == null ? "" : session.sessionId())
        );
        Long stepId = null;
        try {
            stepId = agentRunService.startStep(
                    runId,
                    AgentStepName.GUIDE_ACTION_DECISION,
                    "message=" + compact(message, 300)
            );
            UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
            GuideDtos.GuideDecision ruleDecision = ruleBasedDecision(message, session);
            GuideDtos.GuideDecision decision = llmClient.chatForJson(
                            systemPrompt(),
                            userPrompt(message, session, memory, ruleDecision),
                            GuideDtos.GuideActionModelResponse.class
                    )
                    .map(output -> normalizeModelDecision(message, session, output, ruleDecision))
                    .orElse(ruleDecision);
            decision = guardDecision(message, session, decision, ruleDecision);

            traceArtifactService.record(
                    runId,
                    AgentStepName.GUIDE_ACTION_DECISION,
                    "GUIDE_ACTION_DECISION",
                    decisionArtifact(message, session, ruleDecision, decision),
                    "action=" + decision.action() + ", confidence=" + decision.confidence()
            );
            agentRunService.completeStep(
                    stepId,
                    "action=" + decision.action() + ", shouldCallRag=" + decision.shouldCallRag()
            );
            agentRunService.finishRun(runId, decision.reason());
            return decision;
        } catch (RuntimeException | Error ex) {
            if (stepId != null) {
                agentRunService.failStep(stepId, ex);
            }
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private GuideDtos.GuideDecision normalizeModelDecision(String message,
                                                          GuideDtos.GuideSessionView session,
                                                          GuideDtos.GuideActionModelResponse output,
                                                          GuideDtos.GuideDecision fallback) {
        if (output == null) {
            return fallback;
        }
        GuideAction action = parseAction(output.action(), fallback.action());
        double confidence = clamp(output.confidence() == null ? fallback.confidence() : output.confidence());
        String currentGoal = firstPresent(output.currentGoal(), fallback.currentGoal(), currentGoal(session), message);
        String ragNeed = firstPresent(output.ragNeed(), fallback.ragNeed(), buildRagNeed(action, message, session));
        String reason = firstPresent(output.reason(), fallback.reason(), "LLM action decision");
        String assistantHint = firstPresent(output.assistantHint(), fallback.assistantHint(), defaultHint(action));
        List<String> accepted = limitStrings(firstPresentList(output.acceptedPreferences(), fallback.acceptedPreferences()), 12);
        List<String> rejected = limitStrings(firstPresentList(output.rejectedConditions(), fallback.rejectedConditions()), 12);
        return new GuideDtos.GuideDecision(
                action,
                shouldCallRag(action),
                confidence,
                reason,
                currentGoal,
                ragNeed,
                assistantHint,
                accepted,
                rejected
        );
    }

    private GuideDtos.GuideDecision guardDecision(String message,
                                                 GuideDtos.GuideSessionView session,
                                                 GuideDtos.GuideDecision decision,
                                                 GuideDtos.GuideDecision fallback) {
        String normalized = normalize(message);
        boolean hasSessionGoal = hasText(currentGoal(session));
        boolean hasRecommendations = session != null
                && session.lastRecommendedEventIds() != null
                && !session.lastRecommendedEventIds().isEmpty();

        if (!hasText(message)) {
            return askClarify("用户输入为空，需要先追问。", session);
        }
        if (!hasSessionGoal && (isRefineOnly(normalized) || isContinueRequest(normalized))) {
            return askClarify("没有上一轮搜索目标，补充条件无法单独检索。", session);
        }
        if (!hasRecommendations && decision.action() == GuideAction.CHAT_ONLY && isSearchLike(normalized)) {
            return fallback;
        }
        if (decision.action() == GuideAction.CALL_RAG_REFINE && !hasSessionGoal) {
            return newSearch(message, "没有可继承的上一轮目标，按新搜索处理。", session);
        }
        return decision;
    }

    private GuideDtos.GuideDecision ruleBasedDecision(String message, GuideDtos.GuideSessionView session) {
        String normalized = normalize(message);
        if (!hasText(message)) {
            return askClarify("用户输入为空。", session);
        }

        boolean hasSessionGoal = hasText(currentGoal(session));
        boolean hasRecommendations = session != null
                && session.lastRecommendedEventIds() != null
                && !session.lastRecommendedEventIds().isEmpty();

        if ((hasRecommendations || hasSessionGoal) && isChatOnly(normalized)) {
            return decision(
                    GuideAction.CHAT_ONLY,
                    0.78,
                    "用户在解释、比较或追问已有推荐，不需要重新检索。",
                    currentGoal(session),
                    "",
                    "我可以直接基于上一轮推荐继续解释。",
                    acceptedPreferences(normalized),
                    rejectedConditions(normalized)
            );
        }

        if (hasSessionGoal && (isContinueRequest(normalized) || isRefineOnly(normalized))) {
            return decision(
                    GuideAction.CALL_RAG_REFINE,
                    0.82,
                    "用户在上一轮目标上补充约束或要求更多结果。",
                    currentGoal(session),
                    buildRagNeed(GuideAction.CALL_RAG_REFINE, message, session),
                    "我会在上一轮目标上追加这些条件再检索。",
                    acceptedPreferences(normalized),
                    rejectedConditions(normalized)
            );
        }

        String previousTopic = topicOf(currentGoal(session));
        String currentTopic = topicOf(normalized);
        if (hasSessionGoal && hasText(currentTopic) && hasText(previousTopic) && !currentTopic.equals(previousTopic)) {
            return newSearch(message, "用户切换了核心目标，需要开启新检索。", session);
        }

        if (isSearchLike(normalized) || hasText(currentTopic)) {
            return newSearch(message, "用户提出了新的可检索成长目标。", session);
        }

        return askClarify("需求还不够具体，先追问以避免无意义检索。", session);
    }

    private GuideDtos.GuideDecision newSearch(String message, String reason, GuideDtos.GuideSessionView session) {
        return decision(
                GuideAction.CALL_RAG_NEW,
                0.72,
                reason,
                compact(message, 500),
                compact(message, 1000),
                "我会按这个新目标重新检索。",
                acceptedPreferences(normalize(message)),
                rejectedConditions(normalize(message))
        );
    }

    private GuideDtos.GuideDecision askClarify(String reason, GuideDtos.GuideSessionView session) {
        return decision(
                GuideAction.ASK_CLARIFY,
                0.7,
                reason,
                currentGoal(session),
                "",
                "你可以补充想提升的方向、地点、线上线下或难度偏好。",
                List.of(),
                List.of()
        );
    }

    private GuideDtos.GuideDecision decision(GuideAction action,
                                             double confidence,
                                             String reason,
                                             String currentGoal,
                                             String ragNeed,
                                             String assistantHint,
                                             List<String> acceptedPreferences,
                                             List<String> rejectedConditions) {
        return new GuideDtos.GuideDecision(
                action,
                shouldCallRag(action),
                clamp(confidence),
                safe(reason),
                safe(currentGoal),
                safe(ragNeed),
                safe(assistantHint),
                limitStrings(acceptedPreferences, 12),
                limitStrings(rejectedConditions, 12)
        );
    }

    private String systemPrompt() {
        return """
                你是 do not miss 的 Guide Agent 动作判断器。
                你不负责推荐活动，不负责写最终回复，只判断下一步动作。
                动作只能从下面四个中选择：
                - CALL_RAG_NEW：用户换了新目标，或没有上一轮目标但提出了可检索需求。
                - CALL_RAG_REFINE：用户在上一轮目标上补充条件，例如“最好线上”“更偏 Java”“不要线下”“再来几个”。
                - CHAT_ONLY：用户只是解释、比较、追问已有推荐，或普通聊天，不需要重新检索。
                - ASK_CLARIFY：需求过于模糊，直接检索会很差，应先追问。

                判断原则：
                1. 不要生成活动，不要编造推荐结果。
                2. 如果用户只是补充地点、时间、难度、收益、线上线下，并且存在 currentGoal，优先 CALL_RAG_REFINE。
                3. 如果用户从“学日语”切到“学编程”，或从“健身”切到“AI Agent”，优先 CALL_RAG_NEW。
                4. 如果用户问“为什么推荐这个”“这两个哪个好”“解释一下”，优先 CHAT_ONLY。
                5. 如果没有上下文而用户只说“线上”“再来点”“都行”，优先 ASK_CLARIFY。
                6. acceptedPreferences 记录用户明确想要的偏好，rejectedConditions 记录用户明确不要的条件。

                只输出 JSON，不要输出 Markdown。
                JSON 字段：
                {
                  "action": "CALL_RAG_NEW/CALL_RAG_REFINE/CHAT_ONLY/ASK_CLARIFY",
                  "confidence": 0.0,
                  "reason": "判断理由",
                  "currentGoal": "当前核心目标",
                  "ragNeed": "如果要调用 RAG，传给 RAG 的需求；否则留空",
                  "assistantHint": "给后续回复模块的简短提示",
                  "acceptedPreferences": ["最多12个"],
                  "rejectedConditions": ["最多12个"]
                }
                """;
    }

    private String userPrompt(String message,
                              GuideDtos.GuideSessionView session,
                              UserMemoryDtos.Profile memory,
                              GuideDtos.GuideDecision ruleDecision) {
        return """
                当前用户输入：
                %s

                引导者会话状态：
                sessionId=%s
                currentGoal=%s
                sessionSummary=%s
                lastAction=%s
                lastRagNeed=%s
                recentMessages=%s
                lastRecommendedEventIds=%s
                acceptedPreferences=%s
                rejectedConditions=%s

                学生长期画像弱参考：
                %s

                规则兜底判断：
                action=%s
                reason=%s
                ragNeed=%s
                """.formatted(
                safe(message),
                safe(session == null ? "" : session.sessionId()),
                safe(currentGoal(session)),
                safe(session == null ? "" : session.sessionSummary()),
                safe(session == null ? "" : session.lastAction()),
                safe(session == null ? "" : session.lastRagNeed()),
                session == null ? List.of() : session.recentMessages(),
                session == null ? List.of() : session.lastRecommendedEventIds(),
                session == null ? List.of() : session.acceptedPreferences(),
                session == null ? List.of() : session.rejectedConditions(),
                userMemoryService.promptText(memory),
                ruleDecision.action(),
                ruleDecision.reason(),
                ruleDecision.ragNeed()
        );
    }

    private Map<String, Object> decisionArtifact(String message,
                                                 GuideDtos.GuideSessionView session,
                                                 GuideDtos.GuideDecision ruleDecision,
                                                 GuideDtos.GuideDecision finalDecision) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("message", safe(message));
        artifact.put("sessionId", safe(session == null ? "" : session.sessionId()));
        artifact.put("currentGoalBefore", safe(currentGoal(session)));
        artifact.put("ruleDecision", ruleDecision);
        artifact.put("finalDecision", finalDecision);
        return artifact;
    }

    private String buildRagNeed(GuideAction action, String message, GuideDtos.GuideSessionView session) {
        if (action == GuideAction.CALL_RAG_REFINE && hasText(currentGoal(session))) {
            return compact(currentGoal(session) + "；补充条件：" + safe(message), 1000);
        }
        return compact(message, 1000);
    }

    private boolean shouldCallRag(GuideAction action) {
        return action == GuideAction.CALL_RAG_NEW || action == GuideAction.CALL_RAG_REFINE;
    }

    private GuideAction parseAction(String value, GuideAction fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return GuideAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private boolean isChatOnly(String normalized) {
        return containsAny(normalized, "为什么", "原因", "解释", "说明", "比较", "哪个好", "哪一个好",
                "区别", "刚才", "上面", "这个活动", "这几个", "推荐理由", "详细说", "什么意思");
    }

    private boolean isContinueRequest(String normalized) {
        return containsAny(normalized, "还有", "再推荐", "换一批", "更多", "继续推荐", "再来");
    }

    private boolean isRefineOnly(String normalized) {
        return containsAny(normalized, "最好", "更", "偏", "不要", "不想", "不太想", "线上", "线下",
                "大阪", "东京", "周末", "晚上", "下午", "上午", "低难度", "难度低", "零基础", "有报酬", "有偿");
    }

    private boolean isSearchLike(String normalized) {
        return containsAny(normalized, "想学", "想找", "推荐", "参加", "活动", "实践", "提升", "锻炼",
                "学习", "训练", "项目", "比赛", "实习", "规划", "计划");
    }

    private String topicOf(String value) {
        String normalized = normalize(value);
        if (containsAny(normalized, "日语", "日本语", "n1", "n2", "n3", "n4", "n5", "留学", "口语")) {
            return "language_japanese";
        }
        if (containsAny(normalized, "agent", "llm", "大模型", "rag", "人工智能", "智能体")) {
            return "ai_agent";
        }
        if (containsAny(normalized, "java", "spring", "python", "go", "编程", "程序", "后端", "前端", "开发")) {
            return "programming";
        }
        if (containsAny(normalized, "健身", "锻炼", "减肥", "运动", "跑步", "身体")) {
            return "fitness";
        }
        if (containsAny(normalized, "研究", "调研", "论文", "问卷", "访谈")) {
            return "research";
        }
        return "";
    }

    private List<String> acceptedPreferences(String normalized) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (containsAny(normalized, "线上", "远程")) {
            values.add("线上优先");
        }
        if (containsAny(normalized, "线下")) {
            values.add("线下可参与");
        }
        if (containsAny(normalized, "大阪")) {
            values.add("大阪");
        }
        if (containsAny(normalized, "东京")) {
            values.add("东京");
        }
        if (containsAny(normalized, "java")) {
            values.add("Java");
        }
        if (containsAny(normalized, "零基础", "从零", "初学")) {
            values.add("适合零基础");
        }
        if (containsAny(normalized, "有报酬", "有偿", "钱")) {
            values.add("有报酬");
        }
        return List.copyOf(values);
    }

    private List<String> rejectedConditions(String normalized) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (containsAny(normalized, "不要线下", "不想线下", "不太想去线下", "不想去线下")) {
            values.add("不要线下");
        }
        if (containsAny(normalized, "不要太难", "不想太难", "难度别太高")) {
            values.add("不要高难度");
        }
        if (containsAny(normalized, "不要没报酬", "不想没钱")) {
            values.add("不要无报酬");
        }
        return List.copyOf(values);
    }

    private String currentGoal(GuideDtos.GuideSessionView session) {
        return session == null ? "" : safe(session.currentGoal());
    }

    private String defaultHint(GuideAction action) {
        return switch (action) {
            case CALL_RAG_NEW -> "按新目标重新检索。";
            case CALL_RAG_REFINE -> "继承上一轮目标并追加条件检索。";
            case CHAT_ONLY -> "不调用 RAG，围绕已有结果回答。";
            case ASK_CLARIFY -> "先追问一个关键条件。";
        };
    }

    private boolean containsAny(String value, String... terms) {
        if (!hasText(value)) {
            return false;
        }
        for (String term : terms) {
            if (value.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> firstPresentList(List<String> first, List<String> second) {
        return first != null && !first.isEmpty() ? first : (second == null ? List.of() : second);
    }

    private List<String> limitStrings(List<String> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                result.add(compact(value, 80));
            }
            if (result.size() >= maxSize) {
                break;
            }
        }
        return result;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
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
