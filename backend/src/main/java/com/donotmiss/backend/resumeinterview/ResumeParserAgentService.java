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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResumeParserAgentService {
    public static final String PROMPT_VERSION = "resume-parser-v1.0";

    private final ResumeInterviewSessionRepository sessionRepository;
    private final ResumeSnapshotRepository snapshotRepository;
    private final ResumeInterviewStateService stateService;
    private final ResumeInterviewService resumeInterviewService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;
    private final ObjectMapper objectMapper;

    public ResumeParserAgentService(ResumeInterviewSessionRepository sessionRepository,
                                    ResumeSnapshotRepository snapshotRepository,
                                    ResumeInterviewStateService stateService,
                                    ResumeInterviewService resumeInterviewService,
                                    OpenAiCompatibleLlmClient llmClient,
                                    AgentRunService agentRunService,
                                    AgentTraceArtifactService traceArtifactService,
                                    ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.snapshotRepository = snapshotRepository;
        this.stateService = stateService;
        this.resumeInterviewService = resumeInterviewService;
        this.llmClient = llmClient;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
        this.objectMapper = objectMapper;
    }

    public ResumeInterviewDtos.ResumeAssessmentDetailResponse parse(String userId, Long sessionId) {
        ResumeInterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, normalizeUserId(userId))
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
        if (session.getStatus() == ResumeInterviewSessionStatus.RESUME_PARSED) {
            return resumeInterviewService.getDetail(userId, sessionId);
        }
        if (session.getStatus() != ResumeInterviewSessionStatus.CREATED) {
            throw ApiException.badRequest("当前状态不能解析简历：" + session.getStatus());
        }

        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.RESUME_PARSER,
                "解析简历事实：" + session.getTargetRole(),
                "sessionId=" + session.getId() + ", snapshotId=" + session.getActiveResumeSnapshotId()
        );
        Long loadStep = null;
        Long extractionStep = null;
        Long validationStep = null;
        try {
            loadStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_SNAPSHOT_LOAD,
                    "Load active resume text snapshot without sending raw content to trace."
            );
            ResumeSnapshotEntity snapshot = activeSnapshot(session);
            agentRunService.completeStep(
                    loadStep,
                    "snapshotId=" + snapshot.getId()
                            + ", hash=" + snapshot.getResumeHash()
                            + ", length=" + safe(snapshot.getRawResumeText()).length()
            );
            loadStep = null;

            extractionStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_FACT_EXTRACTION,
                    "Extract education, projects, internships, contests, certificates and skills. No scoring."
            );
            ParsedResumeOutput rawOutput = extractFacts(session, snapshot);
            ResumeInterviewDtos.ParsedResume rawParsed = rawOutput.parsed();
            agentRunService.completeStep(extractionStep, "mode=" + rawOutput.mode());
            extractionStep = null;

            validationStep = agentRunService.startStep(
                    runId,
                    AgentStepName.RESUME_FACT_VALIDATION,
                    "Normalize parsed facts, trim fields and keep only factual sections."
            );
            ResumeInterviewDtos.ParsedResume parsed = sanitize(rawParsed);
            persistParsed(session, snapshot, parsed, runId, rawOutput.mode());
            traceArtifactService.record(
                    runId,
                    AgentStepName.RESUME_FACT_VALIDATION,
                    "RESUME_PARSED_FACTS",
                    Map.of(
                            "sessionId", session.getId(),
                            "snapshotId", snapshot.getId(),
                            "resumeHash", snapshot.getResumeHash(),
                            "promptVersion", PROMPT_VERSION,
                            "model", rawOutput.mode(),
                            "parsed", parsed
                    ),
                    summary(parsed)
            );
            agentRunService.completeStep(validationStep, summary(parsed));
            validationStep = null;
            agentRunService.finishRun(runId, "resumeParsed=true, " + summary(parsed));
            return resumeInterviewService.getDetail(userId, sessionId);
        } catch (RuntimeException | Error ex) {
            if (loadStep != null) {
                agentRunService.failStep(loadStep, ex);
            }
            if (extractionStep != null) {
                agentRunService.failStep(extractionStep, ex);
            }
            if (validationStep != null) {
                agentRunService.failStep(validationStep, ex);
            }
            markFailed(session, ex);
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private ResumeSnapshotEntity activeSnapshot(ResumeInterviewSessionEntity session) {
        Long snapshotId = session.getActiveResumeSnapshotId();
        if (snapshotId == null) {
            throw ApiException.badRequest("当前会话没有可解析的简历快照");
        }
        return snapshotRepository.findByIdAndSessionId(snapshotId, session.getId())
                .orElseThrow(() -> ApiException.notFound("简历快照不存在"));
    }

    private ParsedResumeOutput extractFacts(ResumeInterviewSessionEntity session,
                                            ResumeSnapshotEntity snapshot) {
        String resumeText = safe(snapshot.getRawResumeText());
        if (llmClient.isEnabled()) {
            return llmClient.chatForJson(
                            systemPrompt(),
                            userPrompt(session, snapshot),
                            ResumeInterviewDtos.ParsedResume.class
                    )
                    .map(parsed -> new ParsedResumeOutput(parsed, llmClient.modeLabel()))
                    .orElseGet(() -> new ParsedResumeOutput(localFallback(resumeText), "rule-fallback"));
        }
        return new ParsedResumeOutput(localFallback(resumeText), "rule");
    }

    @Transactional
    protected void persistParsed(ResumeInterviewSessionEntity session,
                                 ResumeSnapshotEntity snapshot,
                                 ResumeInterviewDtos.ParsedResume parsed,
                                 Long runId,
                                 String mode) {
        snapshot.setParsedResumeJson(toJson(parsed));
        snapshot.setParserPromptVersion(PROMPT_VERSION);
        snapshot.setParserModelName(mode);
        snapshot.setParserTraceRunId(runId);
        snapshotRepository.save(snapshot);

        stateService.moveTo(session, ResumeInterviewSessionStatus.RESUME_PARSED);
        session.setActiveResumeSnapshotId(snapshot.getId());
        sessionRepository.save(session);
    }

    @Transactional
    protected void markFailed(ResumeInterviewSessionEntity session, Throwable ex) {
        if (session == null || session.getStatus() != ResumeInterviewSessionStatus.CREATED) {
            return;
        }
        try {
            stateService.moveTo(session, ResumeInterviewSessionStatus.FAILED);
            session.setErrorMessage(compact(errorMessage(ex), 1200));
            sessionRepository.save(session);
        } catch (RuntimeException ignored) {
            // 状态失败记录不能覆盖原始异常。
        }
    }

    private ResumeInterviewDtos.ParsedResume sanitize(ResumeInterviewDtos.ParsedResume parsed) {
        if (parsed == null) {
            parsed = emptyParsed(List.of("LLM 未返回可用 JSON，已降级为空结构。"));
        }
        List<String> warnings = new ArrayList<>(strings(parsed.parserWarnings(), 20, 180));
        ResumeInterviewDtos.ParsedResume result = new ResumeInterviewDtos.ParsedResume(
                sections(parsed.education(), "education"),
                sections(parsed.projects(), "project"),
                sections(parsed.internships(), "internship"),
                sections(parsed.contests(), "contest"),
                sections(parsed.certificates(), "certificate"),
                strings(parsed.skills(), 40, 80),
                strings(parsed.claimedRoles(), 12, 120),
                strings(parsed.weakSignals(), 20, 180),
                warnings
        );
        if (isEmpty(result)) {
            warnings.add("未能抽取出明确经历，请检查简历文本是否过短或格式过乱。");
            result = new ResumeInterviewDtos.ParsedResume(
                    result.education(),
                    result.projects(),
                    result.internships(),
                    result.contests(),
                    result.certificates(),
                    result.skills(),
                    result.claimedRoles(),
                    result.weakSignals(),
                    strings(warnings, 20, 180)
            );
        }
        return result;
    }

    private List<ResumeInterviewDtos.ResumeSection> sections(List<ResumeInterviewDtos.ResumeSection> sections,
                                                             String idPrefix) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<ResumeInterviewDtos.ResumeSection> result = new ArrayList<>();
        int index = 1;
        for (ResumeInterviewDtos.ResumeSection section : sections) {
            if (section == null) {
                continue;
            }
            String title = compact(firstPresent(section.title(), section.description()), 160);
            String description = compact(section.description(), 1000);
            if (title.isBlank() && description.isBlank()) {
                continue;
            }
            result.add(new ResumeInterviewDtos.ResumeSection(
                    compact(firstPresent(section.id(), idPrefix + "-" + index), 80),
                    title,
                    compact(section.organization(), 160),
                    compact(section.timeRange(), 120),
                    description,
                    strings(section.evidenceRefs(), 8, 120)
            ));
            index++;
            if (result.size() >= 12) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private ResumeInterviewDtos.ParsedResume localFallback(String resumeText) {
        List<String> lines = resumeText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(80)
                .toList();
        List<ResumeInterviewDtos.ResumeSection> education = new ArrayList<>();
        List<ResumeInterviewDtos.ResumeSection> projects = new ArrayList<>();
        List<ResumeInterviewDtos.ResumeSection> internships = new ArrayList<>();
        List<ResumeInterviewDtos.ResumeSection> contests = new ArrayList<>();
        List<ResumeInterviewDtos.ResumeSection> certificates = new ArrayList<>();
        LinkedHashSet<String> skills = new LinkedHashSet<>();

        int index = 1;
        for (String line : lines) {
            String normalized = line.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, "大学", "university", "本科", "硕士", "修士", "研究生", "学院")) {
                education.add(section("education-" + index++, line, "", line));
            } else if (containsAny(normalized, "实习", "公司", "工作", "internship", "backend", "后端")) {
                internships.add(section("internship-" + index++, line, "", line));
            } else if (containsAny(normalized, "比赛", "竞赛", "天池", "contest", "kaggle", "获奖")) {
                contests.add(section("contest-" + index++, line, "", line));
            } else if (containsAny(normalized, "证书", "cet", "雅思", "jlpt", "toeic", "托福", "资格")) {
                certificates.add(section("certificate-" + index++, line, "", line));
            } else if (containsAny(normalized, "项目", "project", "rag", "agent", "spring", "mysql", "redis", "opensearch")) {
                projects.add(section("project-" + index++, line, "", line));
            }
            if (containsAny(normalized, "java", "spring", "mysql", "redis", "rabbitmq", "opensearch", "rag", "agent", "llm", "vue", "docker")) {
                addKnownSkills(normalized, skills);
            }
        }

        return new ResumeInterviewDtos.ParsedResume(
                education,
                projects,
                internships,
                contests,
                certificates,
                List.copyOf(skills),
                List.of(),
                List.of("本地规则只做粗抽取，后续建议用 LLM Parser 复核。"),
                List.of(llmClient.isEnabled() ? "LLM 返回格式不可用，已使用本地规则降级。" : "未配置 LLM，已使用本地规则降级。")
        );
    }

    private ResumeInterviewDtos.ResumeSection section(String id, String title, String organization, String description) {
        return new ResumeInterviewDtos.ResumeSection(
                id,
                compact(title, 160),
                compact(organization, 160),
                "",
                compact(description, 1000),
                List.of(id)
        );
    }

    private ResumeInterviewDtos.ParsedResume emptyParsed(List<String> warnings) {
        return new ResumeInterviewDtos.ParsedResume(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                warnings
        );
    }

    private boolean isEmpty(ResumeInterviewDtos.ParsedResume parsed) {
        return parsed.education().isEmpty()
                && parsed.projects().isEmpty()
                && parsed.internships().isEmpty()
                && parsed.contests().isEmpty()
                && parsed.certificates().isEmpty()
                && parsed.skills().isEmpty();
    }

    private void addKnownSkills(String normalized, LinkedHashSet<String> skills) {
        for (String skill : List.of("Java", "Spring", "MySQL", "Redis", "RabbitMQ", "OpenSearch", "RAG", "Agent", "LLM", "Vue", "Docker")) {
            if (normalized.contains(skill.toLowerCase(Locale.ROOT))) {
                skills.add(skill);
            }
        }
    }

    private String systemPrompt() {
        return """
                你是 do not miss 的 Resume Parser Agent。
                你的职责只有一个：把用户粘贴的简历拆成结构化事实。
                你不能评分，不能判断强弱，不能建议能力分，不能生成面试问题，不能夸大简历。

                只允许抽取简历文本中明确出现或高度确定的事实：
                - education：教育经历
                - projects：项目经历
                - internships：实习/工作经历
                - contests：比赛/科研/奖项经历
                - certificates：证书/语言成绩/资格
                - skills：技能关键词
                - claimedRoles：候选人自称或经历中体现的角色，例如 Java 后端、Agent 工程、前端开发
                - weakSignals：信息不足、时间不明、职责不清、缺少结果等弱信号
                - parserWarnings：解析过程中的风险提示

                ResumeSection 字段：
                {
                  "id": "稳定短 ID，例如 project-1",
                  "title": "经历标题",
                  "organization": "学校/公司/组织，没有则空字符串",
                  "timeRange": "时间范围，没有则空字符串",
                  "description": "事实描述，不要评价",
                  "evidenceRefs": ["引用原文中的关键词或短句，最多 8 个"]
                }

                输出必须是 JSON 对象，不要 Markdown，不要解释。
                JSON 格式：
                {
                  "education": [],
                  "projects": [],
                  "internships": [],
                  "contests": [],
                  "certificates": [],
                  "skills": [],
                  "claimedRoles": [],
                  "weakSignals": [],
                  "parserWarnings": []
                }
                """;
    }

    private String userPrompt(ResumeInterviewSessionEntity session, ResumeSnapshotEntity snapshot) {
        return """
                目标岗位：%s
                简历 SHA-256：%s

                简历原文：
                %s
                """.formatted(
                safe(session.getTargetRole()),
                safe(snapshot.getResumeHash()),
                safe(snapshot.getRawResumeText())
        );
    }

    private String summary(ResumeInterviewDtos.ParsedResume parsed) {
        return "education=" + parsed.education().size()
                + ", projects=" + parsed.projects().size()
                + ", internships=" + parsed.internships().size()
                + ", contests=" + parsed.contests().size()
                + ", certificates=" + parsed.certificates().size()
                + ", skills=" + parsed.skills().size();
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

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : safe(second);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize parsed resume", ex);
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

    private record ParsedResumeOutput(
            ResumeInterviewDtos.ParsedResume parsed,
            String mode
    ) {
    }
}
