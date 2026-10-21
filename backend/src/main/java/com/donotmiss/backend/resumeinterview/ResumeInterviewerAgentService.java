package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResumeInterviewerAgentService {
    public static final String PROMPT_VERSION = "resume-interviewer-v1.0";

    private final ResumeInterviewSessionRepository sessionRepository;
    private final ResumeSnapshotRepository snapshotRepository;
    private final ResumeInterviewQuestionRepository questionRepository;
    private final ResumeInterviewStateService stateService;
    private final ResumeInterviewService resumeInterviewService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ResumeInterviewerAgentService(ResumeInterviewSessionRepository sessionRepository,
                                         ResumeSnapshotRepository snapshotRepository,
                                         ResumeInterviewQuestionRepository questionRepository,
                                         ResumeInterviewStateService stateService,
                                         ResumeInterviewService resumeInterviewService,
                                         OpenAiCompatibleLlmClient llmClient,
                                         AgentRunService agentRunService,
                                         AgentTraceArtifactService traceArtifactService,
                                         ObjectMapper objectMapper,
                                         TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.snapshotRepository = snapshotRepository;
        this.questionRepository = questionRepository;
        this.stateService = stateService;
        this.resumeInterviewService = resumeInterviewService;
        this.llmClient = llmClient;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ResumeInterviewDtos.ResumeAssessmentDetailResponse generateQuestions(String userId, Long sessionId) {
        String normalizedUserId = normalizeUserId(userId);
        ResumeInterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, normalizedUserId)
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));

        List<ResumeInterviewQuestionEntity> existing =
                questionRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
        if (!existing.isEmpty()) {
            ensureInterviewing(session);
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        }
        if (session.getStatus() != ResumeInterviewSessionStatus.RESUME_PARSED) {
            throw ApiException.badRequest("当前状态不能生成面试追问：" + session.getStatus());
        }

        Long runId = agentRunService.startRun(
                normalizedUserId,
                AgentRunType.RESUME_INTERVIEWER,
                "生成简历追问：" + session.getTargetRole(),
                "sessionId=" + session.getId() + ", snapshotId=" + session.getActiveResumeSnapshotId()
        );
        Long loadStep = null;
        Long focusStep = null;
        Long generationStep = null;
        Long validationStep = null;
        try {
            loadStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_FACT_LOAD,
                    "Load parsed resume JSON. Interviewer does not read raw resume text."
            );
            ResumeSnapshotEntity snapshot = activeSnapshot(session);
            ResumeInterviewDtos.ParsedResume parsed = readParsedResume(snapshot);
            agentRunService.completeStep(loadStep, factSummary(parsed));
            loadStep = null;

            focusStep = agentRunService.startStep(
                    runId,
                    AgentStepName.INTERVIEW_FOCUS_SELECTION,
                    "Select high-value verification points from parsed facts and target role."
            );
            List<String> localFocus = localVerificationFocus(parsed, session.getTargetRole());
            agentRunService.completeStep(focusStep, "focus=" + String.join(", ", localFocus));
            focusStep = null;

            generationStep = agentRunService.startStep(
                    runId,
                    AgentStepName.INTERVIEW_QUESTION_GENERATION,
                    "Generate 3-5 interview questions. No scoring, no final judgment."
            );
            QuestionGenerationOutput output = generateQuestionDrafts(session, parsed, localFocus);
            agentRunService.completeStep(generationStep, "mode=" + output.mode());
            generationStep = null;

            validationStep = agentRunService.startStep(
                    runId,
                    AgentStepName.INTERVIEW_QUESTION_VALIDATION,
                    "Normalize questions, deduplicate and persist as ASKED."
            );
            List<ResumeInterviewDtos.InterviewQuestionDraft> questions =
                    sanitizeQuestions(output.response().questions(), parsed, localFocus);
            persistQuestions(session, questions, runId);
            traceArtifactService.record(
                    runId,
                    AgentStepName.INTERVIEW_QUESTION_VALIDATION,
                    "RESUME_INTERVIEW_QUESTIONS",
                    Map.of(
                            "sessionId", session.getId(),
                            "snapshotId", snapshot.getId(),
                            "resumeHash", snapshot.getResumeHash(),
                            "promptVersion", PROMPT_VERSION,
                            "model", output.mode(),
                            "targetRole", safe(session.getTargetRole()),
                            "localFocus", localFocus,
                            "questions", questions,
                            "warnings", output.response().warnings() == null ? List.of() : output.response().warnings()
                    ),
                    "questions=" + questions.size() + ", mode=" + output.mode()
            );
            agentRunService.completeStep(validationStep, "questions=" + questions.size());
            validationStep = null;
            agentRunService.finishRun(runId, "interviewing=true, questions=" + questions.size());
            return resumeInterviewService.getDetail(normalizedUserId, sessionId);
        } catch (RuntimeException | Error ex) {
            if (loadStep != null) {
                agentRunService.failStep(loadStep, ex);
            }
            if (focusStep != null) {
                agentRunService.failStep(focusStep, ex);
            }
            if (generationStep != null) {
                agentRunService.failStep(generationStep, ex);
            }
            if (validationStep != null) {
                agentRunService.failStep(validationStep, ex);
            }
            markFailed(session, ex);
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private void ensureInterviewing(ResumeInterviewSessionEntity session) {
        if (session.getStatus() == ResumeInterviewSessionStatus.RESUME_PARSED) {
            transactionTemplate.execute(status -> {
                stateService.moveTo(session, ResumeInterviewSessionStatus.INTERVIEWING);
                sessionRepository.save(session);
                return null;
            });
        }
    }

    private ResumeSnapshotEntity activeSnapshot(ResumeInterviewSessionEntity session) {
        Long snapshotId = session.getActiveResumeSnapshotId();
        if (snapshotId == null) {
            throw ApiException.badRequest("当前会话没有可用于追问的简历快照");
        }
        return snapshotRepository.findByIdAndSessionId(snapshotId, session.getId())
                .orElseThrow(() -> ApiException.notFound("简历快照不存在"));
    }

    private ResumeInterviewDtos.ParsedResume readParsedResume(ResumeSnapshotEntity snapshot) {
        String json = snapshot.getParsedResumeJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            throw ApiException.badRequest("简历尚未结构化，请先执行 Resume Parser Agent");
        }
        try {
            return objectMapper.readValue(json, ResumeInterviewDtos.ParsedResume.class);
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("简历结构化结果无法读取，请重新解析简历");
        }
    }

    private QuestionGenerationOutput generateQuestionDrafts(ResumeInterviewSessionEntity session,
                                                            ResumeInterviewDtos.ParsedResume parsed,
                                                            List<String> localFocus) {
        if (llmClient.isEnabled()) {
            return llmClient.chatForJson(
                            systemPrompt(),
                            userPrompt(session, parsed, localFocus),
                            ResumeInterviewDtos.InterviewQuestionGenerationModelResponse.class
                    )
                    .map(response -> new QuestionGenerationOutput(response, llmClient.modeLabel()))
                    .orElseGet(() -> new QuestionGenerationOutput(localFallback(parsed, localFocus), "rule-fallback"));
        }
        return new QuestionGenerationOutput(localFallback(parsed, localFocus), "rule");
    }

    private List<ResumeInterviewDtos.InterviewQuestionDraft> sanitizeQuestions(
            List<ResumeInterviewDtos.InterviewQuestionDraft> rawQuestions,
            ResumeInterviewDtos.ParsedResume parsed,
            List<String> localFocus
    ) {
        List<ResumeInterviewDtos.InterviewQuestionDraft> questions = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int sequence = 1;
        for (ResumeInterviewDtos.InterviewQuestionDraft question : rawQuestions == null ? List.<ResumeInterviewDtos.InterviewQuestionDraft>of() : rawQuestions) {
            if (question == null) {
                continue;
            }
            String text = compact(question.questionText(), 1000);
            if (text.length() < 12) {
                continue;
            }
            String key = normalizeQuestionKey(text);
            if (!seen.add(key)) {
                continue;
            }
            String targetDimension = compact(firstPresent(question.targetDimension(), inferDimension(text, localFocus)), 120);
            questions.add(new ResumeInterviewDtos.InterviewQuestionDraft(
                    sequence,
                    targetDimension,
                    compact(firstPresent(question.normalizedDimension(), normalizeDimension(targetDimension)), 120),
                    text,
                    strings(question.evidenceRefs(), 8, 120),
                    compact(firstPresent(question.reason(), "验证简历中与 " + targetDimension + " 相关的事实深度。"), 1000)
            ));
            sequence++;
            if (questions.size() >= 5) {
                break;
            }
        }
        if (questions.size() < 3) {
            for (ResumeInterviewDtos.InterviewQuestionDraft fallback : localFallback(parsed, localFocus).questions()) {
                String key = normalizeQuestionKey(fallback.questionText());
                if (seen.add(key)) {
                    questions.add(new ResumeInterviewDtos.InterviewQuestionDraft(
                            sequence,
                            fallback.targetDimension(),
                            fallback.normalizedDimension(),
                            fallback.questionText(),
                            fallback.evidenceRefs(),
                            fallback.reason()
                    ));
                    sequence++;
                }
                if (questions.size() >= 3) {
                    break;
                }
            }
        }
        return questions.size() > 5 ? questions.subList(0, 5) : questions;
    }

    private void persistQuestions(ResumeInterviewSessionEntity session,
                                  List<ResumeInterviewDtos.InterviewQuestionDraft> questions,
                                  Long runId) {
        transactionTemplate.execute(status -> {
            List<ResumeInterviewQuestionEntity> existing =
                    questionRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
            if (!existing.isEmpty()) {
                if (session.getStatus() == ResumeInterviewSessionStatus.RESUME_PARSED) {
                    stateService.moveTo(session, ResumeInterviewSessionStatus.INTERVIEWING);
                    sessionRepository.save(session);
                }
                return null;
            }
            for (ResumeInterviewDtos.InterviewQuestionDraft draft : questions) {
                ResumeInterviewQuestionEntity entity = new ResumeInterviewQuestionEntity();
                entity.setSessionId(session.getId());
                entity.setSequenceNo(draft.sequenceNo());
                entity.setStatus(ResumeInterviewQuestionStatus.ASKED);
                entity.setTargetDimension(draft.targetDimension());
                entity.setNormalizedDimension(draft.normalizedDimension());
                entity.setQuestionText(draft.questionText());
                entity.setEvidenceRefsJson(toJson(draft.evidenceRefs()));
                entity.setGenerationReason(draft.reason());
                entity.setAgentTraceRunId(runId);
                questionRepository.save(entity);
            }
            stateService.moveTo(session, ResumeInterviewSessionStatus.INTERVIEWING);
            sessionRepository.save(session);
            return null;
        });
    }

    private void markFailed(ResumeInterviewSessionEntity session, Throwable ex) {
        if (session == null || session.getStatus() == ResumeInterviewSessionStatus.FAILED) {
            return;
        }
        if (session.getStatus() != ResumeInterviewSessionStatus.RESUME_PARSED) {
            return;
        }
        transactionTemplate.execute(status -> {
            stateService.moveTo(session, ResumeInterviewSessionStatus.FAILED);
            session.setErrorMessage(compact(errorMessage(ex), 1200));
            sessionRepository.save(session);
            return null;
        });
    }

    private ResumeInterviewDtos.InterviewQuestionGenerationModelResponse localFallback(
            ResumeInterviewDtos.ParsedResume parsed,
            List<String> localFocus
    ) {
        List<ResumeInterviewDtos.InterviewQuestionDraft> questions = new ArrayList<>();
        List<String> refs = firstEvidenceRefs(parsed);
        int sequence = 1;
        for (String focus : localFocus) {
            questions.add(new ResumeInterviewDtos.InterviewQuestionDraft(
                    sequence,
                    focus,
                    normalizeDimension(focus),
                    questionFor(focus),
                    refs,
                    "根据简历中出现的经历和目标岗位，优先验证 " + focus + " 的真实掌握程度。"
            ));
            sequence++;
            if (questions.size() >= 5) {
                break;
            }
        }
        while (questions.size() < 3) {
            String focus = switch (questions.size()) {
                case 0 -> "项目复盘能力";
                case 1 -> "工程细节理解";
                default -> "学习迁移能力";
            };
            questions.add(new ResumeInterviewDtos.InterviewQuestionDraft(
                    questions.size() + 1,
                    focus,
                    normalizeDimension(focus),
                    questionFor(focus),
                    refs,
                    "简历信息不足时，用通用工程追问补足验证。"
            ));
        }
        return new ResumeInterviewDtos.InterviewQuestionGenerationModelResponse(
                questions,
                localFocus,
                List.of(llmClient.isEnabled() ? "LLM 返回不可用，已使用规则追问。" : "未配置 LLM，已使用规则追问。")
        );
    }

    private String questionFor(String focus) {
        String normalized = focus.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "rag", "检索", "召回", "重排")) {
            return "你在简历里提到 RAG 相关经历。请具体说明一次完整查询从用户输入到最终回答的链路：Query Rewrite、召回、重排、上下文压缩分别解决什么问题？";
        }
        if (containsAny(normalized, "agent", "multi", "工作流", "tool")) {
            return "你提到 Agent 相关项目。请讲一个你设计过的 Agent 流程：每个 Agent 或步骤分别负责什么，什么时候需要调用工具，失败时如何兜底？";
        }
        if (containsAny(normalized, "java", "spring", "后端")) {
            return "请结合你的 Java/Spring 项目经历，说明一个核心接口从 Controller 到 Service、数据库和中间件的调用链路，以及你如何保证异常和事务边界。";
        }
        if (containsAny(normalized, "mysql", "redis", "mq", "rabbit", "opensearch", "数据库", "中间件")) {
            return "你简历里出现了数据库或中间件。请选一个最熟的场景，说明为什么引入它、数据一致性怎么保证、失败重试或幂等如何处理。";
        }
        if (containsAny(normalized, "前端", "vue", "页面")) {
            return "你提到前端或 Vue 经验。请说明一个页面状态如何从用户操作变成 API 请求，再回到页面渲染，期间如何处理 loading、错误和登录状态。";
        }
        return "请选一个你简历里最能代表该能力的经历，说明你具体做了什么、遇到什么问题、怎么验证最终效果。";
    }

    private List<String> localVerificationFocus(ResumeInterviewDtos.ParsedResume parsed, String targetRole) {
        LinkedHashSet<String> focus = new LinkedHashSet<>();
        String combined = (targetRole + " " + toJson(parsed)).toLowerCase(Locale.ROOT);
        if (containsAny(combined, "rag", "retrieval", "召回", "重排", "opensearch", "vector", "embedding")) {
            focus.add("RAG 检索与重排能力");
        }
        if (containsAny(combined, "agent", "multi-agent", "tool", "mcp", "workflow", "planner")) {
            focus.add("Agent 工作流设计能力");
        }
        if (containsAny(combined, "java", "spring", "springboot", "backend", "后端")) {
            focus.add("Java 后端工程能力");
        }
        if (containsAny(combined, "mysql", "redis", "rabbitmq", "mq", "opensearch", "kafka", "outbox")) {
            focus.add("数据库与中间件一致性能力");
        }
        if (containsAny(combined, "vue", "frontend", "前端", "页面")) {
            focus.add("前端工程与交互实现能力");
        }
        if (focus.isEmpty()) {
            focus.add("项目复盘能力");
            focus.add("工程细节理解");
            focus.add("学习迁移能力");
        }
        return focus.stream().limit(5).toList();
    }

    private List<String> firstEvidenceRefs(ResumeInterviewDtos.ParsedResume parsed) {
        List<String> refs = new ArrayList<>();
        collectRefs(refs, parsed.projects());
        collectRefs(refs, parsed.internships());
        collectRefs(refs, parsed.contests());
        collectRefs(refs, parsed.certificates());
        collectRefs(refs, parsed.education());
        if (refs.isEmpty()) {
            refs.add("parsed-resume");
        }
        return refs.stream().limit(8).toList();
    }

    private void collectRefs(List<String> refs, List<ResumeInterviewDtos.ResumeSection> sections) {
        if (sections == null) {
            return;
        }
        for (ResumeInterviewDtos.ResumeSection section : sections) {
            if (section == null) {
                continue;
            }
            if (section.id() != null && !section.id().isBlank()) {
                refs.add(section.id());
            } else if (section.title() != null && !section.title().isBlank()) {
                refs.add(section.title());
            }
            if (refs.size() >= 8) {
                return;
            }
        }
    }

    private String systemPrompt() {
        return """
                你是 do not miss 的 Interviewer Agent。
                你的职责：根据结构化简历和目标岗位，生成 3-5 个最值得验证的面试追问。
                你不能评分，不能输出能力分，不能判断候选人强弱，不能生成最终评价。
                你只能提出用于验证事实深度的问题。

                追问原则：
                1. 优先验证简历中“看起来重要但需要确认深度”的能力点。
                2. 问题必须具体，能迫使用户回答工程细节，而不是泛泛而谈。
                3. 如果简历提到 RAG，应追问召回、重排、评测、上下文压缩、索引同步等细节。
                4. 如果简历提到 Agent，应追问 Agent 分工、状态流、工具调用、失败兜底、trace/bad case 等细节。
                5. 如果简历提到 Java 后端，应追问接口链路、事务、幂等、异步消息、数据库索引等细节。
                6. 每个问题要关联一个 targetDimension，并填写 evidenceRefs 指向简历结构中的 section id 或关键词。
                7. 不要问隐私问题，不要要求用户透露公司机密、真实 key、账号密码等。

                输出必须是 JSON 对象，不要 Markdown。
                JSON 格式：
                {
                  "questions": [
                    {
                      "sequenceNo": 1,
                      "targetDimension": "RAG 检索与重排能力",
                      "normalizedDimension": "rag-retrieval-rerank",
                      "questionText": "具体追问",
                      "evidenceRefs": ["project-1"],
                      "reason": "为什么需要验证这个点"
                    }
                  ],
                  "verificationPriorities": ["优先验证的能力点"],
                  "warnings": []
                }
                """;
    }

    private String userPrompt(ResumeInterviewSessionEntity session,
                              ResumeInterviewDtos.ParsedResume parsed,
                              List<String> localFocus) {
        return """
                目标岗位：%s
                本地预判优先验证点：%s

                结构化简历 JSON：
                %s
                """.formatted(
                safe(session.getTargetRole()),
                String.join("、", localFocus),
                toJson(parsed)
        );
    }

    private String factSummary(ResumeInterviewDtos.ParsedResume parsed) {
        return "education=" + size(parsed.education())
                + ", projects=" + size(parsed.projects())
                + ", internships=" + size(parsed.internships())
                + ", contests=" + size(parsed.contests())
                + ", certificates=" + size(parsed.certificates())
                + ", skills=" + size(parsed.skills());
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private List<String> strings(List<String> values, int maxSize, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String cleaned = compact(value, maxLength);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
            if (result.size() >= maxSize) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String inferDimension(String text, List<String> localFocus) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String focus : localFocus) {
            if (containsAny(normalized, focus.toLowerCase(Locale.ROOT))) {
                return focus;
            }
        }
        if (containsAny(normalized, "rag", "召回", "重排")) {
            return "RAG 检索与重排能力";
        }
        if (containsAny(normalized, "agent", "工具", "状态")) {
            return "Agent 工作流设计能力";
        }
        if (containsAny(normalized, "java", "spring", "事务")) {
            return "Java 后端工程能力";
        }
        return localFocus.isEmpty() ? "项目复盘能力" : localFocus.get(0);
    }

    private String normalizeDimension(String value) {
        String text = Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");
        return text.isBlank() ? "general-project-review" : compact(text, 120);
    }

    private String normalizeQuestionKey(String value) {
        return safe(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : safe(second);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize interview question payload", ex);
        }
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String compact(String value, int maxLength) {
        String text = safe(value);
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record QuestionGenerationOutput(
            ResumeInterviewDtos.InterviewQuestionGenerationModelResponse response,
            String mode
    ) {
    }
}
