package com.donotmiss.backend.agentlog;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentBadCaseIntakeAgent {
    private static final int MAX_ARTIFACTS_FOR_LLM = 10;

    private final AgentRunStepRepository stepRepository;
    private final AgentTraceArtifactRepository artifactRepository;
    private final AgentRunService agentRunService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean llmTriageEnabled;

    public AgentBadCaseIntakeAgent(AgentRunStepRepository stepRepository,
                                   AgentTraceArtifactRepository artifactRepository,
                                   AgentRunService agentRunService,
                                   OpenAiCompatibleLlmClient llmClient,
                                   ObjectMapper objectMapper,
                                   @Value("${app.ai.bad-case-intake-llm-enabled:true}") boolean llmTriageEnabled) {
        this.stepRepository = stepRepository;
        this.artifactRepository = artifactRepository;
        this.agentRunService = agentRunService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.llmTriageEnabled = llmTriageEnabled;
    }

    public void triage(String userId, AgentBadCaseEntity badCase, AgentRunEntity sourceRun) {
        Long intakeRunId = agentRunService.startRun(
                userId,
                AgentRunType.BAD_CASE_INTAKE,
                "Triage bad case " + badCase.getCaseKey(),
                badCase.getUserMessage()
        );
        Long currentStepId = null;
        try {
            currentStepId = agentRunService.startStep(
                    intakeRunId,
                    AgentStepName.BAD_CASE_TRACE_LOAD,
                    "sourceRunId=" + badCase.getAgentRunId()
            );
            TraceBundle trace = loadTrace(sourceRun);
            agentRunService.completeStep(
                    currentStepId,
                    "steps=" + trace.steps().size() + ", artifacts=" + trace.artifacts().size()
            );

            currentStepId = agentRunService.startStep(
                    intakeRunId,
                    AgentStepName.BAD_CASE_TRIAGE,
                    "issue=" + badCase.getIssueType() + ", severity=" + badCase.getSeverity()
            );
            IntakeDecision decision = analyze(badCase, sourceRun, trace);
            applyDecision(badCase, sourceRun, trace, intakeRunId, decision);
            agentRunService.completeStep(
                    currentStepId,
                    "root=" + decision.rootCauseStep() + ", issue=" + decision.issueType()
            );
            agentRunService.finishRun(intakeRunId, compact(badCase.getRootCauseSummary(), 1000));
        } catch (Exception ex) {
            if (currentStepId != null) {
                agentRunService.failStep(currentStepId, ex);
            }
            agentRunService.failRun(intakeRunId, ex);
            applyLocalFailure(badCase, intakeRunId, ex);
        }
    }

    private TraceBundle loadTrace(AgentRunEntity sourceRun) {
        if (sourceRun == null) {
            return new TraceBundle(List.of(), List.of());
        }
        List<AgentRunStepEntity> steps = stepRepository.findByRunIdOrderBySequenceNoAsc(sourceRun.getId());
        List<AgentTraceArtifactEntity> artifacts = artifactRepository.findByRunIdOrderByIdAsc(sourceRun.getId());
        return new TraceBundle(steps, artifacts);
    }

    private IntakeDecision analyze(AgentBadCaseEntity badCase, AgentRunEntity sourceRun, TraceBundle trace) {
        IntakeDecision ruleDecision = ruleBasedDecision(badCase, trace);
        if (!llmTriageEnabled || !llmClient.isEnabled() || sourceRun == null || trace.steps().isEmpty()) {
            return ruleDecision;
        }

        Optional<LlmDecision> llmDecision = llmClient.chatForJson(
                systemPrompt(),
                userPrompt(badCase, sourceRun, trace, ruleDecision),
                LlmDecision.class
        );
        return llmDecision
                .map(decision -> merge(ruleDecision, decision))
                .orElse(ruleDecision);
    }

    private IntakeDecision ruleBasedDecision(AgentBadCaseEntity badCase, TraceBundle trace) {
        AgentRunStepEntity failed = trace.steps().stream()
                .filter(step -> step.getStatus() == AgentStepStatus.FAILED)
                .findFirst()
                .orElse(null);
        AgentRunStepEntity slowest = trace.steps().stream()
                .max(Comparator.comparingLong(AgentRunStepEntity::durationMillis))
                .orElse(null);

        AgentStepName rootStep = failed == null ? null : failed.getStepName();
        String reason = failed == null ? "" : "Step failed: " + compact(failed.getErrorMessage(), 240);

        if (rootStep == null) {
            rootStep = inferRootStep(badCase.getIssueType(), badCase.getUserMessage(), trace, slowest);
            reason = reasonFor(badCase.getIssueType(), rootStep, slowest);
        }

        List<Long> artifactIds = relevantArtifacts(rootStep, trace.artifacts());
        AgentBadCaseIssueType issue = normalizeIssue(badCase.getIssueType(), rootStep, badCase.getUserMessage());
        AgentBadCaseSeverity severity = normalizeSeverity(badCase.getSeverity(), issue, slowest);

        return new IntakeDecision(
                "rule",
                issue,
                severity,
                rootStep,
                rootStep == null ? "用户反馈已记录，但没有关联可分析的 Agent Trace。" : reason,
                artifactIds,
                shouldWriteMemory(issue),
                shouldEnterEval(issue),
                0.55,
                "Local rules used issue type, failed step, slowest step and artifact types."
        );
    }

    private AgentStepName inferRootStep(AgentBadCaseIssueType issueType,
                                        String message,
                                        TraceBundle trace,
                                        AgentRunStepEntity slowest) {
        String normalized = normalizeText(message);
        return switch (issueType == null ? AgentBadCaseIssueType.UNKNOWN : issueType) {
            case QUERY_REWRITE_ERROR, WRONG_CONTEXT -> AgentStepName.QUERY_REWRITE;
            case RETRIEVAL_MISS -> AgentStepName.RETRIEVAL;
            case LOW_RELEVANCE, HALLUCINATION -> hasStep(trace, AgentStepName.LLM_RECOMMENDATION)
                    ? AgentStepName.LLM_RECOMMENDATION
                    : AgentStepName.CRITIC_REVIEW;
            case PLAN_UNEXECUTABLE -> hasStep(trace, AgentStepName.SCHEDULE_CHECK)
                    ? AgentStepName.SCHEDULE_CHECK
                    : AgentStepName.CRITIC_REVIEW;
            case COACH_OFF_TOPIC -> AgentStepName.LLM_RECOMMENDATION;
            case SCORING_UNFAIR -> hasStep(trace, AgentStepName.DETERMINISTIC_SCORING)
                    ? AgentStepName.DETERMINISTIC_SCORING
                    : AgentStepName.EVIDENCE_EXTRACTION;
            case TOOL_ERROR -> hasStep(trace, AgentStepName.MCP_CONTEXT)
                    ? AgentStepName.MCP_CONTEXT
                    : failedOrSlowest(slowest);
            case LATENCY_TIMEOUT -> failedOrSlowest(slowest);
            case UI_CONFUSION, UNKNOWN -> {
                if (containsAny(normalized, "召回", "没出现", "没返回", "漏", "推荐不到")) {
                    yield AgentStepName.RETRIEVAL;
                }
                if (containsAny(normalized, "理解错", "上下文", "接着", "上一句")) {
                    yield AgentStepName.QUERY_REWRITE;
                }
                if (containsAny(normalized, "计划", "流程", "排期", "日程")) {
                    yield AgentStepName.CRITIC_REVIEW;
                }
                yield failedOrSlowest(slowest);
            }
        };
    }

    private AgentStepName failedOrSlowest(AgentRunStepEntity slowest) {
        return slowest == null ? null : slowest.getStepName();
    }

    private boolean hasStep(TraceBundle trace, AgentStepName stepName) {
        return trace.steps().stream().anyMatch(step -> step.getStepName() == stepName);
    }

    private String reasonFor(AgentBadCaseIssueType issueType, AgentStepName rootStep, AgentRunStepEntity slowest) {
        if (rootStep == null) {
            return "用户反馈已记录，但没有足够 trace 判断根因。";
        }
        if (issueType == AgentBadCaseIssueType.LATENCY_TIMEOUT && slowest != null) {
            return "响应慢的主要嫌疑步骤是 " + rootStep + "，该步骤耗时 " + slowest.durationMillis() + "ms。";
        }
        return "根据反馈类型和 trace 结构，优先定位到 " + rootStep + " 步骤。";
    }

    private AgentBadCaseIssueType normalizeIssue(AgentBadCaseIssueType issueType, AgentStepName rootStep, String message) {
        if (issueType != null && issueType != AgentBadCaseIssueType.UNKNOWN) {
            return issueType;
        }
        String normalized = normalizeText(message);
        if (containsAny(normalized, "慢", "卡", "超时", "等很久")) {
            return AgentBadCaseIssueType.LATENCY_TIMEOUT;
        }
        if (containsAny(normalized, "没出现", "没返回", "漏", "召回")) {
            return AgentBadCaseIssueType.RETRIEVAL_MISS;
        }
        if (containsAny(normalized, "不相关", "牵强", "不符合")) {
            return AgentBadCaseIssueType.LOW_RELEVANCE;
        }
        if (containsAny(normalized, "编造", "不存在", "乱生成")) {
            return AgentBadCaseIssueType.HALLUCINATION;
        }
        if (rootStep == AgentStepName.QUERY_REWRITE) {
            return AgentBadCaseIssueType.QUERY_REWRITE_ERROR;
        }
        if (rootStep == AgentStepName.RETRIEVAL) {
            return AgentBadCaseIssueType.RETRIEVAL_MISS;
        }
        return AgentBadCaseIssueType.UNKNOWN;
    }

    private AgentBadCaseSeverity normalizeSeverity(AgentBadCaseSeverity severity,
                                                   AgentBadCaseIssueType issue,
                                                   AgentRunStepEntity slowest) {
        if (severity != null && severity != AgentBadCaseSeverity.MEDIUM) {
            return severity;
        }
        if (issue == AgentBadCaseIssueType.TOOL_ERROR || issue == AgentBadCaseIssueType.HALLUCINATION) {
            return AgentBadCaseSeverity.HIGH;
        }
        if (issue == AgentBadCaseIssueType.LATENCY_TIMEOUT && slowest != null && slowest.durationMillis() > 20_000) {
            return AgentBadCaseSeverity.HIGH;
        }
        return severity == null ? AgentBadCaseSeverity.MEDIUM : severity;
    }

    private List<Long> relevantArtifacts(AgentStepName rootStep, List<AgentTraceArtifactEntity> artifacts) {
        if (artifacts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (AgentTraceArtifactEntity artifact : artifacts) {
            if (rootStep != null && artifact.getStepName() == rootStep) {
                ids.add(artifact.getId());
            }
        }
        if (ids.isEmpty()) {
            for (AgentTraceArtifactEntity artifact : artifacts) {
                String type = artifact.getArtifactType();
                if ("FINAL_RESPONSE".equals(type)
                        || type.endsWith("_OUTPUT")
                        || type.contains("CANDIDATES")
                        || type.contains("RESULT")) {
                    ids.add(artifact.getId());
                }
                if (ids.size() >= 6) {
                    break;
                }
            }
        }
        return ids.stream().limit(8).toList();
    }

    private IntakeDecision merge(IntakeDecision fallback, LlmDecision llmDecision) {
        AgentBadCaseIssueType issue = safeEnum(
                AgentBadCaseIssueType.class,
                llmDecision.issueType(),
                fallback.issueType()
        );
        AgentBadCaseSeverity severity = safeEnum(
                AgentBadCaseSeverity.class,
                llmDecision.severity(),
                fallback.severity()
        );
        AgentStepName rootStep = safeEnum(
                AgentStepName.class,
                llmDecision.rootCauseStep(),
                fallback.rootCauseStep()
        );
        List<Long> artifactIds = llmDecision.relevantArtifactIds() == null || llmDecision.relevantArtifactIds().isEmpty()
                ? fallback.relevantArtifactIds()
                : llmDecision.relevantArtifactIds().stream().limit(8).toList();
        double confidence = llmDecision.confidence() == null
                ? 0.72
                : Math.max(0.0, Math.min(1.0, llmDecision.confidence()));
        return new IntakeDecision(
                "llm",
                issue,
                severity,
                rootStep,
                compact(defaultText(llmDecision.rootCauseSummary(), fallback.rootCauseSummary()), 2000),
                artifactIds,
                llmDecision.agentMemoryCandidate() == null
                        ? fallback.agentMemoryCandidate()
                        : llmDecision.agentMemoryCandidate(),
                llmDecision.evalCaseCandidate() == null
                        ? fallback.evalCaseCandidate()
                        : llmDecision.evalCaseCandidate(),
                confidence,
                compact(defaultText(llmDecision.reasoning(), fallback.reasoning()), 1600)
        );
    }

    private void applyDecision(AgentBadCaseEntity badCase,
                               AgentRunEntity sourceRun,
                               TraceBundle trace,
                               Long intakeRunId,
                               IntakeDecision decision) {
        badCase.setIssueType(decision.issueType());
        badCase.setSeverity(decision.severity());
        badCase.setRootCauseStep(decision.rootCauseStep());
        badCase.setRootCauseSummary(compact(decision.rootCauseSummary(), 2000));
        badCase.setRelevantArtifactIds(toJson(decision.relevantArtifactIds()));
        badCase.setAgentMemoryCandidate(decision.agentMemoryCandidate());
        badCase.setEvalCaseCandidate(decision.evalCaseCandidate());
        badCase.setStatus(AgentBadCaseStatus.TRIAGED);
        badCase.setTriagedAt(Instant.now());
        badCase.setAnalysisJson(toJson(analysisPayload(badCase, sourceRun, trace, intakeRunId, decision, null)));
    }

    private void applyLocalFailure(AgentBadCaseEntity badCase, Long intakeRunId, Exception ex) {
        badCase.setStatus(AgentBadCaseStatus.OPEN);
        badCase.setRootCauseSummary("Bad Case Intake Agent 分析失败，反馈已保留，后续可人工复盘。");
        badCase.setAnalysisJson(toJson(analysisPayload(badCase, null, new TraceBundle(List.of(), List.of()),
                intakeRunId, null, ex)));
    }

    private Map<String, Object> analysisPayload(AgentBadCaseEntity badCase,
                                                AgentRunEntity sourceRun,
                                                TraceBundle trace,
                                                Long intakeRunId,
                                                IntakeDecision decision,
                                                Exception error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intakeRunId", intakeRunId);
        payload.put("sourceRunId", badCase.getAgentRunId());
        payload.put("sourceRunType", sourceRun == null ? badCase.getRunType() : sourceRun.getRunType());
        payload.put("mode", decision == null ? "failed" : decision.mode());
        payload.put("modelMode", llmClient.modeLabel());
        payload.put("stepCount", trace.steps().size());
        payload.put("artifactCount", trace.artifacts().size());
        payload.put("issueType", badCase.getIssueType());
        payload.put("severity", badCase.getSeverity());
        payload.put("rootCauseStep", decision == null ? badCase.getRootCauseStep() : decision.rootCauseStep());
        payload.put("confidence", decision == null ? 0.0 : decision.confidence());
        payload.put("reasoning", decision == null ? "" : decision.reasoning());
        payload.put("relevantArtifactIds", decision == null ? List.of() : decision.relevantArtifactIds());
        payload.put("agentMemoryCandidate", decision != null && decision.agentMemoryCandidate());
        payload.put("evalCaseCandidate", decision != null && decision.evalCaseCandidate());
        if (error != null) {
            payload.put("error", compact(error.getMessage(), 500));
        }
        return payload;
    }

    private String systemPrompt() {
        return """
                You are the Bad Case Intake Agent for Do Not Miss.
                Your job is to read a user's complaint, the selected Agent Run trace, and semantic artifacts.
                Return only one JSON object with these fields:
                issueType, severity, rootCauseStep, rootCauseSummary, relevantArtifactIds,
                agentMemoryCandidate, evalCaseCandidate, confidence, reasoning.

                Constraints:
                - issueType must be one of UNKNOWN, RETRIEVAL_MISS, LOW_RELEVANCE, QUERY_REWRITE_ERROR,
                  HALLUCINATION, WRONG_CONTEXT, PLAN_UNEXECUTABLE, COACH_OFF_TOPIC, SCORING_UNFAIR,
                  TOOL_ERROR, UI_CONFUSION, LATENCY_TIMEOUT.
                - severity must be LOW, MEDIUM, HIGH, or CRITICAL.
                - rootCauseStep must be one of the provided step names. Do not invent agent names.
                - relevantArtifactIds must only include artifact ids shown in the trace.
                - rootCauseSummary should be concise Chinese, explaining what likely went wrong.
                - agentMemoryCandidate means this case is useful as long-term reflection memory.
                - evalCaseCandidate means it should become a regression/evaluation case.
                """;
    }

    private String userPrompt(AgentBadCaseEntity badCase,
                              AgentRunEntity sourceRun,
                              TraceBundle trace,
                              IntakeDecision ruleDecision) {
        StringBuilder builder = new StringBuilder();
        builder.append("User feedback:\n")
                .append("issueType=").append(badCase.getIssueType()).append('\n')
                .append("severity=").append(badCase.getSeverity()).append('\n')
                .append("message=").append(compact(badCase.getUserMessage(), 1000)).append('\n')
                .append("expected=").append(compact(badCase.getExpectedBehavior(), 600)).append('\n')
                .append("actual=").append(compact(badCase.getActualBehavior(), 600)).append('\n')
                .append("page=").append(compact(badCase.getPageUrl(), 240)).append('\n')
                .append("module=").append(compact(badCase.getModuleKey(), 80)).append("\n\n");

        builder.append("Source run:\n")
                .append("id=").append(sourceRun.getId()).append('\n')
                .append("type=").append(sourceRun.getRunType()).append('\n')
                .append("status=").append(sourceRun.getStatus()).append('\n')
                .append("goal=").append(compact(sourceRun.getGoal(), 400)).append('\n')
                .append("inputSummary=").append(compact(sourceRun.getInputSummary(), 500)).append('\n')
                .append("outputSummary=").append(compact(sourceRun.getOutputSummary(), 500)).append("\n\n");

        builder.append("Steps:\n");
        for (AgentRunStepEntity step : trace.steps()) {
            builder.append("- ")
                    .append(step.getSequenceNo()).append(' ')
                    .append(step.getStepName())
                    .append(" status=").append(step.getStatus())
                    .append(" durationMs=").append(step.durationMillis())
                    .append(" input=").append(compact(step.getInputSummary(), 260))
                    .append(" output=").append(compact(step.getOutputSummary(), 260));
            if (step.getErrorMessage() != null && !step.getErrorMessage().isBlank()) {
                builder.append(" error=").append(compact(step.getErrorMessage(), 260));
            }
            builder.append('\n');
        }

        builder.append("\nArtifacts:\n");
        trace.artifacts().stream().limit(MAX_ARTIFACTS_FOR_LLM).forEach(artifact -> builder
                .append("- id=").append(artifact.getId())
                .append(" step=").append(artifact.getStepName())
                .append(" type=").append(artifact.getArtifactType())
                .append(" summary=").append(compact(artifact.getContentSummary(), 260))
                .append(" json=").append(compact(artifact.getContentJson(), 900))
                .append('\n'));

        builder.append("\nLocal rule hint:\n")
                .append("rootCauseStep=").append(ruleDecision.rootCauseStep()).append('\n')
                .append("summary=").append(ruleDecision.rootCauseSummary()).append('\n')
                .append("artifactIds=").append(ruleDecision.relevantArtifactIds()).append('\n');
        return builder.toString();
    }

    private boolean shouldWriteMemory(AgentBadCaseIssueType issue) {
        return issue != AgentBadCaseIssueType.UNKNOWN
                && issue != AgentBadCaseIssueType.UI_CONFUSION
                && issue != AgentBadCaseIssueType.LATENCY_TIMEOUT;
    }

    private boolean shouldEnterEval(AgentBadCaseIssueType issue) {
        return issue == AgentBadCaseIssueType.RETRIEVAL_MISS
                || issue == AgentBadCaseIssueType.LOW_RELEVANCE
                || issue == AgentBadCaseIssueType.QUERY_REWRITE_ERROR
                || issue == AgentBadCaseIssueType.WRONG_CONTEXT
                || issue == AgentBadCaseIssueType.PLAN_UNEXECUTABLE
                || issue == AgentBadCaseIssueType.SCORING_UNFAIR;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private <T extends Enum<T>> T safeEnum(Class<T> enumType, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private record TraceBundle(
            List<AgentRunStepEntity> steps,
            List<AgentTraceArtifactEntity> artifacts
    ) {
    }

    private record IntakeDecision(
            String mode,
            AgentBadCaseIssueType issueType,
            AgentBadCaseSeverity severity,
            AgentStepName rootCauseStep,
            String rootCauseSummary,
            List<Long> relevantArtifactIds,
            boolean agentMemoryCandidate,
            boolean evalCaseCandidate,
            double confidence,
            String reasoning
    ) {
    }

    private record LlmDecision(
            String issueType,
            String severity,
            String rootCauseStep,
            String rootCauseSummary,
            List<Long> relevantArtifactIds,
            Boolean agentMemoryCandidate,
            Boolean evalCaseCandidate,
            Double confidence,
            String reasoning
    ) {
    }
}
