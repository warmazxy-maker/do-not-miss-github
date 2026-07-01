package com.donotmiss.backend.ai;

import com.donotmiss.backend.achievement.AchievementDtos;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.achievement.GrowthTagEntity;
import com.donotmiss.backend.achievement.GrowthTagEvidenceEntity;
import com.donotmiss.backend.achievement.GrowthTagEvidenceRepository;
import com.donotmiss.backend.achievement.GrowthTagRepository;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepName;
import com.donotmiss.backend.agentlog.AgentTraceArtifactService;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import com.donotmiss.backend.mcp.McpDtos;
import com.donotmiss.backend.mcp.McpToolContextService;
import com.donotmiss.backend.retrieval.HybridEventRetrievalService;
import com.donotmiss.backend.retrieval.QueryRewriteService;
import com.donotmiss.backend.retrieval.RetrievedEvent;
import com.donotmiss.backend.retrieval.RetrievalDtos;
import com.donotmiss.backend.retrieval.SearchSessionContextService;
import com.donotmiss.backend.schedule.ScheduleDtos;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class AiService {
    private static final int MAX_MODEL_EVENTS = 20;
    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int MIN_MODEL_SCORE = 55;
    private static final int MIN_RULE_SCORE = 50;

    private final EventService eventService;
    private final AchievementService achievementService;
    private final GrowthTagRepository growthTagRepository;
    private final GrowthTagEvidenceRepository growthTagEvidenceRepository;
    private final UserMemoryService userMemoryService;
    private final HybridEventRetrievalService hybridEventRetrievalService;
    private final QueryRewriteService queryRewriteService;
    private final SearchSessionContextService searchSessionContextService;
    private final ScheduleService scheduleService;
    private final McpToolContextService mcpToolContextService;
    private final AgentRunService agentRunService;
    private final AgentTraceArtifactService traceArtifactService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final String aiMode;

    public AiService(EventService eventService,
                     AchievementService achievementService,
                     GrowthTagRepository growthTagRepository,
                     GrowthTagEvidenceRepository growthTagEvidenceRepository,
                     UserMemoryService userMemoryService,
                     HybridEventRetrievalService hybridEventRetrievalService,
                     QueryRewriteService queryRewriteService,
                     SearchSessionContextService searchSessionContextService,
                     ScheduleService scheduleService,
                     McpToolContextService mcpToolContextService,
                     AgentRunService agentRunService,
                     AgentTraceArtifactService traceArtifactService,
                     OpenAiCompatibleLlmClient llmClient,
                     ObjectMapper objectMapper,
                     @Value("${app.ai.provider:mock}") String aiMode) {
        this.eventService = eventService;
        this.achievementService = achievementService;
        this.growthTagRepository = growthTagRepository;
        this.growthTagEvidenceRepository = growthTagEvidenceRepository;
        this.userMemoryService = userMemoryService;
        this.hybridEventRetrievalService = hybridEventRetrievalService;
        this.queryRewriteService = queryRewriteService;
        this.searchSessionContextService = searchSessionContextService;
        this.scheduleService = scheduleService;
        this.mcpToolContextService = mcpToolContextService;
        this.agentRunService = agentRunService;
        this.traceArtifactService = traceArtifactService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.aiMode = aiMode;
    }

    @Transactional(readOnly = true)
    public AiDtos.EventRecommendationResponse recommendEvents(String userId, AiDtos.EventRecommendationRequest request) {
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.EVENT_RECOMMENDATION,
                request.need(),
                "need=" + request.need() + ", category=" + nullToEmpty(request.category())
                        + ", benefitType=" + nullToEmpty(request.benefitType())
                        + ", location=" + nullToEmpty(request.location())
        );
        try {
            UserMemoryDtos.Profile memory = tracedStep(runId, AgentStepName.PROFILE_MEMORY,
                    "Load compressed student memory.",
                    () -> userMemoryService.profile(userId),
                    this::summarizeMemory);
            McpDtos.ToolContextResponse toolContext = tracedStep(runId, AgentStepName.MCP_CONTEXT,
                    "Resolve MCP time/location context.",
                    () -> mcpToolContextService.resolve(request.toolContext()),
                    this::summarizeToolContext);
            RetrievalDtos.SearchSessionContext previousContext = searchSessionContextService.load(userId).orElse(null);
            RetrievalDtos.QueryRewrite query = tracedStep(runId, AgentStepName.QUERY_REWRITE,
                    "Rewrite need and choose search context action: " + request.need(),
                    () -> queryRewriteService.rewrite(request.need(), memory, previousContext),
                    rewrite -> "mode=" + rewrite.mode() + ", goal=" + rewrite.goal()
                            + ", contextAction=" + rewrite.contextDecision().action()
                            + ", relation=" + rewrite.contextDecision().relation()
                            + ", tags=" + rewrite.intentTags());
            RetrievalDtos.SearchSessionContext savedContext = searchSessionContextService.save(
                    userId,
                    query,
                    "CLEAR".equalsIgnoreCase(query.contextDecision().action()) ? null : previousContext
            );
            recordArtifact(
                    runId,
                    AgentStepName.QUERY_REWRITE,
                    "QUERY_REWRITE_RESULT",
                    queryArtifact(query, previousContext, savedContext),
                    "goal=" + query.goal() + ", action=" + query.contextDecision().action()
            );
            AiDtos.EventRecommendationRequest retrievalRequest = new AiDtos.EventRecommendationRequest(
                    query.rewrittenQuery(),
                    request.category(),
                    firstPresent(request.benefitType(), normalizeBenefitPreference(query.benefitPreference())),
                    firstPresent(request.location(), query.preferredLocation()),
                    request.toolContext()
            );
            List<RetrievedEvent> retrievedEvents = tracedStep(runId, AgentStepName.RETRIEVAL,
                    "Hybrid retrieve candidate events.",
                    () -> hybridEventRetrievalService.retrieve(retrievalRequest, memory, toolContext),
                    this::summarizeRetrievedEvents);
            recordArtifact(
                    runId,
                    AgentStepName.RETRIEVAL,
                    "RETRIEVAL_CANDIDATES",
                    retrievalArtifact(retrievedEvents),
                    summarizeRetrievedEvents(retrievedEvents)
            );
            List<EventEntity> candidates = retrievedEvents.stream().map(RetrievedEvent::event).toList();
            Map<Long, RetrievedEvent> retrievedById = retrievedEvents.stream()
                    .collect(Collectors.toMap(item -> item.event().getId(), Function.identity(), (left, right) -> left, LinkedHashMap::new));
            List<AchievementRecordEntity> history = tracedStep(runId, AgentStepName.HISTORY_LOAD,
                    "Load completed achievement history.",
                    () -> achievementService.historyEntities(userId),
                    records -> "historyCount=" + records.size());

            Optional<List<AiDtos.RecommendedEvent>> modelRecommendations = tracedStep(runId, AgentStepName.LLM_RECOMMENDATION,
                    "Ask model to rerank validated candidates.",
                    () -> recommendEventsWithModel(candidates, history, memory, retrievedById, request, query, toolContext),
                    this::summarizeOptionalRecommendations);
            modelRecommendations.ifPresent(items -> recordArtifact(
                    runId,
                    AgentStepName.LLM_RECOMMENDATION,
                    "MODEL_RECOMMENDATION_OUTPUT",
                    recommendationArtifact(items),
                    summarizeRecommendations(items)
            ));

            List<AiDtos.RecommendedEvent> recommendations;
            String mode;
            if (modelRecommendations.isPresent() && !modelRecommendations.get().isEmpty()) {
                recommendations = modelRecommendations.get();
                mode = llmClient.modeLabel();
            } else {
                recommendations = tracedStep(runId, AgentStepName.RULE_FALLBACK,
                        "Model unavailable or returned empty result. Use rule fallback.",
                        () -> rankEventsByRules(candidates, query.rewrittenQuery(), textOfHistory(history), memory, retrievedById),
                        this::summarizeRecommendations);
                recordArtifact(
                        runId,
                        AgentStepName.RULE_FALLBACK,
                        "RULE_FALLBACK_RECOMMENDATION_OUTPUT",
                        recommendationArtifact(recommendations),
                        summarizeRecommendations(recommendations)
                );
                mode = modelRecommendations.isPresent() ? llmClient.modeLabel() + ":rule-fallback" : fallbackMode();
            }

            AiDtos.EventRecommendationResponse response = tracedStep(runId, AgentStepName.RESPONSE_BUILD,
                    "Build event recommendation response.",
                    () -> new AiDtos.EventRecommendationResponse(
                            mode,
                            request.need(),
                            buildRecommendationMessage(recommendations, candidates.size()),
                            memory,
                            recommendations,
                            toolContext
                    ),
                    value -> "mode=" + value.mode() + ", " + summarizeRecommendations(value.recommendations()));
            recordArtifact(
                    runId,
                    AgentStepName.RESPONSE_BUILD,
                    "FINAL_RESPONSE",
                    Map.of(
                            "mode", response.mode(),
                            "message", response.message(),
                            "recommendations", recommendationArtifact(response.recommendations())
                    ),
                    summarizeRecommendations(response.recommendations())
            );
            agentRunService.finishRun(runId, response.message() + " " + summarizeRecommendations(response.recommendations()));
            return response;
        } catch (RuntimeException | Error ex) {
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public UserMemoryDtos.Profile profileMemory(String userId) {
        return userMemoryService.profile(userId);
    }

    @Transactional(readOnly = true)
    public AiDtos.PlanRecommendationResponse recommendPlans(String userId, AiDtos.PlanRecommendationRequest request) {
        int horizonDays = Math.min(Math.max(request.horizonDays() == null ? 21 : request.horizonDays(), 7), 60);
        Long runId = agentRunService.startRun(
                userId,
                AgentRunType.PLAN_RECOMMENDATION,
                request.goal(),
                "goal=" + request.goal() + ", horizonDays=" + horizonDays
                        + ", intensity=" + nullToEmpty(request.intensity())
                        + ", location=" + nullToEmpty(request.location())
        );
        try {
            UserMemoryDtos.Profile memory = tracedStep(runId, AgentStepName.PROFILE_MEMORY,
                    "Load compressed student memory.",
                    () -> userMemoryService.profile(userId),
                    this::summarizeMemory);
            McpDtos.ToolContextResponse toolContext = tracedStep(runId, AgentStepName.MCP_CONTEXT,
                    "Resolve MCP time/location context.",
                    () -> mcpToolContextService.resolve(request.toolContext()),
                    this::summarizeToolContext);
            AiDtos.PlanGoalUnderstanding goal = tracedStep(runId, AgentStepName.GOAL_ANALYSIS,
                    "Goal Agent extracts planning target, constraints and retrieval query.",
                    () -> understandPlanGoal(request, horizonDays),
                    this::summarizePlanGoal);
            recordArtifact(
                    runId,
                    AgentStepName.GOAL_ANALYSIS,
                    "PLAN_GOAL_UNDERSTANDING",
                    planGoalArtifact(goal),
                    summarizePlanGoal(goal)
            );
            List<RetrievedEvent> retrievedEvents = tracedStep(runId, AgentStepName.RETRIEVAL,
                    "Evidence Collector reuses hybrid retrieval to collect plan events.",
                    () -> hybridEventRetrievalService.retrieve(
                            firstPresent(goal.searchQuery(), goal.goal(), request.goal()),
                            null,
                            null,
                            firstPresent(request.location(), goal.preferredLocation()),
                            memory,
                            24,
                            toolContext
                    ),
                    this::summarizeRetrievedEvents);
            recordArtifact(
                    runId,
                    AgentStepName.RETRIEVAL,
                    "PLAN_RETRIEVAL_CANDIDATES",
                    retrievalArtifact(retrievedEvents),
                    summarizeRetrievedEvents(retrievedEvents)
            );
            List<ScheduleDtos.ScheduleItemResponse> schedule = tracedStep(runId, AgentStepName.SCHEDULE_LOAD,
                    "Load current schedule for conflict awareness.",
                    () -> scheduleService.list(userId, null).stream()
                            .limit(20)
                            .toList(),
                    items -> "scheduleCount=" + items.size());
            recordArtifact(
                    runId,
                    AgentStepName.SCHEDULE_LOAD,
                    "SCHEDULE_CONTEXT",
                    scheduleArtifact(schedule),
                    "scheduleCount=" + schedule.size()
            );

            Optional<List<AiDtos.RecommendedPlan>> modelPlans = tracedStep(runId, AgentStepName.LLM_RECOMMENDATION,
                    "Run multi-agent planners and critic.",
                    () -> recommendPlansWithAgents(request, goal, memory, retrievedEvents, schedule, toolContext, runId),
                    this::summarizeOptionalPlans);
            List<AiDtos.RecommendedPlan> plans = modelPlans.orElseGet(() -> tracedStep(runId, AgentStepName.RULE_FALLBACK,
                    "Model unavailable or returned empty plans. Use rule fallback.",
                    () -> checkAndFormatPlans(fallbackPlans(request, goal.horizonDays() == null ? horizonDays : goal.horizonDays(), memory, retrievedEvents, schedule), retrievedEvents, schedule, toolContext),
                    this::summarizePlans));
            recordArtifact(
                    runId,
                    modelPlans.isPresent() ? AgentStepName.LLM_RECOMMENDATION : AgentStepName.RULE_FALLBACK,
                    modelPlans.isPresent() ? "PLAN_MODEL_OUTPUT" : "PLAN_RULE_FALLBACK_OUTPUT",
                    indexedPlanContext(plans),
                    summarizePlans(plans)
            );

            Long responseStepId = agentRunService.startStep(runId, AgentStepName.RESPONSE_BUILD, "Build plan recommendation response.");
            String responseSummary = "mode=" + (modelPlans.isPresent() ? llmClient.modeLabel() : fallbackMode())
                    + ", " + summarizePlans(plans);
            agentRunService.completeStep(responseStepId, responseSummary);
            recordArtifact(
                    runId,
                    AgentStepName.RESPONSE_BUILD,
                    "PLAN_FINAL_RESPONSE",
                    Map.of(
                            "mode", modelPlans.isPresent() ? llmClient.modeLabel() : fallbackMode(),
                            "goal", request.goal(),
                            "plans", indexedPlanContext(plans)
                    ),
                    responseSummary
            );
            agentRunService.finishRun(runId, responseSummary);
            return new AiDtos.PlanRecommendationResponse(
                modelPlans.isPresent() ? llmClient.modeLabel() : fallbackMode(),
                request.goal(),
                "已通过 Goal Agent、复用混合召回的证据收集器、多个 Planner Agent、Schedule Checker 和 Critic Agent 生成 " + plans.size() + " 份行动计划。",
                memory,
                plans,
                toolContext
            );
        } catch (RuntimeException | Error ex) {
            agentRunService.failRun(runId, ex);
            throw ex;
        }
    }

    private <T> T tracedStep(Long runId,
                             AgentStepName stepName,
                             String inputSummary,
                             Supplier<T> action,
                             Function<T, String> outputSummary) {
        Long stepId = agentRunService.startStep(runId, stepName, inputSummary);
        try {
            T result = action.get();
            agentRunService.completeStep(stepId, outputSummary.apply(result));
            return result;
        } catch (RuntimeException | Error ex) {
            agentRunService.failStep(stepId, ex);
            throw ex;
        }
    }

    private String summarizeMemory(UserMemoryDtos.Profile memory) {
        return "completed=" + memory.completedCount()
                + ", activeChallenges=" + memory.activeChallengeCount()
                + ", coachLogs=" + memory.coachLogCount()
                + ", categories=" + firstItems(memory.preferredCategories(), 4)
                + ", keywords=" + firstItems(memory.evidenceKeywords(), 5);
    }

    private String summarizeToolContext(McpDtos.ToolContextResponse toolContext) {
        if (toolContext == null) {
            return "no tool context";
        }
        String date = toolContext.currentTime() == null ? "" : String.valueOf(toolContext.currentTime().localDate());
        String location = toolContext.location() == null ? "" : nullToEmpty(toolContext.location().queryText());
        return "date=" + date + ", location=" + location + ", tools=" + firstItems(toolContext.toolTrace(), 4);
    }

    private String summarizeRetrievedEvents(List<RetrievedEvent> events) {
        List<Long> ids = events.stream()
                .limit(8)
                .map(item -> item.event().getId())
                .toList();
        return "candidateCount=" + events.size() + ", topEventIds=" + ids;
    }

    private String summarizeOptionalRecommendations(Optional<List<AiDtos.RecommendedEvent>> recommendations) {
        return recommendations
                .map(items -> "modelReturned=true, " + summarizeRecommendations(items))
                .orElse("modelReturned=false");
    }

    private String summarizeRecommendations(List<AiDtos.RecommendedEvent> recommendations) {
        List<Long> ids = recommendations.stream()
                .limit(8)
                .map(item -> item.event().id())
                .toList();
        return "recommendationCount=" + recommendations.size() + ", eventIds=" + ids;
    }

    private String summarizeOptionalPlans(Optional<List<AiDtos.RecommendedPlan>> plans) {
        return plans
                .map(items -> "modelReturned=true, " + summarizePlans(items))
                .orElse("modelReturned=false");
    }

    private String summarizePlans(List<AiDtos.RecommendedPlan> plans) {
        List<String> titles = plans.stream()
                .limit(5)
                .map(AiDtos.RecommendedPlan::title)
                .toList();
        int stepCount = plans.stream()
                .mapToInt(plan -> plan.steps() == null ? 0 : plan.steps().size())
                .sum();
        return "planCount=" + plans.size() + ", stepCount=" + stepCount + ", titles=" + titles;
    }

    private String summarizeOptionalPlan(Optional<AiDtos.RecommendedPlan> plan) {
        return plan
                .map(item -> "modelReturned=true, title=" + item.title()
                        + ", style=" + item.style()
                        + ", steps=" + (item.steps() == null ? 0 : item.steps().size())
                        + ", qualityScore=" + item.qualityScore())
                .orElse("modelReturned=false");
    }

    private String summarizePlanGoal(AiDtos.PlanGoalUnderstanding goal) {
        return "goal=" + nullToEmpty(goal.goal())
                + ", level=" + nullToEmpty(goal.level())
                + ", horizonDays=" + goal.horizonDays()
                + ", searchQuery=" + nullToEmpty(goal.searchQuery())
                + ", constraints=" + firstItems(goal.constraints(), 4);
    }

    private List<String> firstItems(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(limit)
                .toList();
    }

    private AiDtos.PlanGoalUnderstanding understandPlanGoal(AiDtos.PlanRecommendationRequest request, int horizonDays) {
        AiDtos.PlanGoalUnderstanding fallback = fallbackGoalUnderstanding(request, horizonDays);
        if (!llmClient.isEnabled()) {
            return fallback;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("goal", request.goal());
        context.put("horizonDays", horizonDays);
        context.put("intensity", nullToEmpty(request.intensity()));
        context.put("location", nullToEmpty(request.location()));

        String systemPrompt = """
                你是 do not miss 的 Goal Agent。
                你的任务是把学生的自然语言目标解析成可用于召回和规划的结构化目标。
                不要生成计划，只做目标理解和检索 query 改写。
                输出严格 JSON，不要 Markdown。
                JSON 格式：
                {
                  "goal":"核心目标",
                  "level":"zero|beginner|intermediate|advanced|unknown",
                  "horizonDays":21,
                  "intensity":"稳妥|集中|探索|未知",
                  "preferredLocation":"线上或城市名，可为空",
                  "constraints":["约束"],
                  "successCriteria":["成功标准"],
                  "searchQuery":"用于活动召回的扩展查询"
                }
                """;
        String userPrompt = """
                请解析下面目标。searchQuery 要适合复用后端混合召回模块，应包含目标、技能、同义词和可参与活动方向。
                输入 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, AiDtos.PlanGoalUnderstanding.class)
                .map(value -> normalizePlanGoal(value, fallback))
                .orElse(fallback);
    }

    private AiDtos.PlanGoalUnderstanding fallbackGoalUnderstanding(AiDtos.PlanRecommendationRequest request, int horizonDays) {
        String goal = cleanText(request.goal(), "成长目标", 160);
        String intensity = firstPresent(request.intensity(), "稳妥");
        String location = nullToEmpty(request.location());
        return new AiDtos.PlanGoalUnderstanding(
                goal,
                "unknown",
                horizonDays,
                intensity,
                location,
                cleanList(List.of(intensity, location), List.of(), 4, 80),
                List.of("完成至少一次行动", "写一次复盘日志", "形成可展示的成长记录"),
                String.join(" ", cleanList(List.of(goal, intensity, location, "实践 活动 学习 挑战 复盘"), List.of(goal), 8, 80))
        );
    }

    private AiDtos.PlanGoalUnderstanding normalizePlanGoal(AiDtos.PlanGoalUnderstanding value,
                                                           AiDtos.PlanGoalUnderstanding fallback) {
        return new AiDtos.PlanGoalUnderstanding(
                cleanText(value.goal(), fallback.goal(), 160),
                cleanText(value.level(), fallback.level(), 40),
                Math.min(Math.max(value.horizonDays() == null ? fallback.horizonDays() : value.horizonDays(), 7), 60),
                cleanText(value.intensity(), fallback.intensity(), 40),
                cleanText(value.preferredLocation(), fallback.preferredLocation(), 80),
                cleanList(value.constraints(), fallback.constraints(), 8, 100),
                cleanList(value.successCriteria(), fallback.successCriteria(), 6, 120),
                cleanText(value.searchQuery(), fallback.searchQuery(), 400)
        );
    }

    private List<EventEntity> findRecommendationCandidates(AiDtos.EventRecommendationRequest request) {
        List<EventEntity> candidates = eventService.searchEntities(new EventDtos.EventSearchRequest(
                null,
                request.category(),
                request.benefitType(),
                request.location()
        ));

        if (!candidates.isEmpty() || hasHardFilter(request)) {
            return candidates;
        }

        return eventService.searchEntities(new EventDtos.EventSearchRequest(null, null, null, null));
    }

    private Optional<List<AiDtos.RecommendedPlan>> recommendPlansWithAgents(AiDtos.PlanRecommendationRequest request,
                                                                            AiDtos.PlanGoalUnderstanding goal,
                                                                            UserMemoryDtos.Profile memory,
                                                                            List<RetrievedEvent> retrievedEvents,
                                                                            List<ScheduleDtos.ScheduleItemResponse> schedule,
                                                                            McpDtos.ToolContextResponse toolContext,
                                                                            Long runId) {
        if (!llmClient.isEnabled()) {
            return Optional.empty();
        }

        PlanEvidencePack evidence = new PlanEvidencePack(request, goal, memory, retrievedEvents, schedule, toolContext);
        List<AiDtos.RecommendedPlan> generated = new ArrayList<>();

        Optional<AiDtos.RecommendedPlan> stablePlan = tracedStep(runId, AgentStepName.PLANNER_STABLE,
                "StablePlannerAgent generates a low-risk plan from retrieval evidence.",
                () -> generatePlannerPlan(evidence, "稳妥型", "StablePlannerAgent",
                        "节奏均匀、压力较低，优先安排高匹配活动、基础学习和复盘。"),
                this::summarizeOptionalPlan
        );
        stablePlan.ifPresent(plan -> {
            generated.add(plan);
            recordArtifact(runId, AgentStepName.PLANNER_STABLE, "PLANNER_STABLE_OUTPUT",
                    indexedPlanContext(List.of(plan)), summarizePlans(List.of(plan)));
        });

        Optional<AiDtos.RecommendedPlan> sprintPlan = tracedStep(runId, AgentStepName.PLANNER_SPRINT,
                "SprintPlannerAgent generates a concentrated breakthrough plan from retrieval evidence.",
                () -> generatePlannerPlan(evidence, "集中突破型", "SprintPlannerAgent",
                        "短周期集中推进，优先安排高强度学习、关键活动和阶段性交付。"),
                this::summarizeOptionalPlan
        );
        sprintPlan.ifPresent(plan -> {
            generated.add(plan);
            recordArtifact(runId, AgentStepName.PLANNER_SPRINT, "PLANNER_SPRINT_OUTPUT",
                    indexedPlanContext(List.of(plan)), summarizePlans(List.of(plan)));
        });

        Optional<AiDtos.RecommendedPlan> explorePlan = tracedStep(runId, AgentStepName.PLANNER_EXPLORE,
                "ExplorePlannerAgent generates an exploratory plan from retrieval evidence.",
                () -> generatePlannerPlan(evidence, "探索型", "ExplorePlannerAgent",
                        "先通过活动和小挑战探索方向，再根据反馈安排学习和复盘。"),
                this::summarizeOptionalPlan
        );
        explorePlan.ifPresent(plan -> {
            generated.add(plan);
            recordArtifact(runId, AgentStepName.PLANNER_EXPLORE, "PLANNER_EXPLORE_OUTPUT",
                    indexedPlanContext(List.of(plan)), summarizePlans(List.of(plan)));
        });

        if (generated.isEmpty()) {
            return Optional.empty();
        }

        List<AiDtos.RecommendedPlan> checked = tracedStep(runId, AgentStepName.SCHEDULE_CHECK,
                "Java Schedule Checker validates event ids, expiration and time conflicts.",
                () -> checkAndFormatPlans(generated, retrievedEvents, schedule, toolContext),
                this::summarizePlans
        );
        recordArtifact(runId, AgentStepName.SCHEDULE_CHECK, "SCHEDULE_CHECK_OUTPUT",
                indexedPlanContext(checked), summarizePlans(checked));

        List<AiDtos.RecommendedPlan> reviewed = tracedStep(runId, AgentStepName.CRITIC_REVIEW,
                "Critic Agent reviews plan quality and keeps executable plans.",
                () -> critiquePlans(checked, evidence),
                this::summarizePlans
        );
        recordArtifact(runId, AgentStepName.CRITIC_REVIEW, "PLAN_CRITIC_OUTPUT",
                indexedPlanContext(reviewed), summarizePlans(reviewed));

        return Optional.of(reviewed.isEmpty() ? checked : reviewed);
    }

    private Optional<AiDtos.RecommendedPlan> generatePlannerPlan(PlanEvidencePack evidence,
                                                                String style,
                                                                String agentName,
                                                                String strategy) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("plannerAgent", agentName);
        context.put("style", style);
        context.put("strategy", strategy);
        context.put("goalUnderstanding", evidence.goal());
        context.put("mcpToolContext", mcpToolContextService.promptContext(evidence.toolContext()));
        context.put("studentMemory", evidence.memory());
        context.put("candidateEvents", evidence.retrievedEvents().stream()
                .limit(15)
                .map(item -> eventContext(item.event(), item))
                .toList());
        context.put("currentSchedule", evidence.schedule().stream().limit(12).toList());

        String systemPrompt = """
                你是 do not miss 的行动计划 Planner Agent。
                你只负责生成一种指定风格的计划，不要生成多份。
                你必须基于 goalUnderstanding、candidateEvents、studentMemory、currentSchedule 和 mcpToolContext 生成计划。
                活动型步骤只能使用 candidateEvents 中真实存在的 eventId；挑战/学习步骤可以不给 eventId。
                不要编造活动、组织、时间或地点。输出严格 JSON，不要 Markdown。
                JSON 格式：
                {
                  "plans": [
                    {
                      "title": "稳妥型计划",
                      "style": "稳妥",
                      "summary": "计划概述",
                      "steps": [
                        {"order":1,"dateLabel":"第1周","title":"步骤标题","itemType":"EVENT","eventId":1,"scheduleHint":"建议周六下午","reason":"原因"}
                      ],
                      "warnings": ["风险提示"],
                      "qualityScore": 82,
                      "agentTrace": ["StablePlannerAgent: 基于召回证据生成"]
                    }
                  ]
                }
                """;

        String userPrompt = """
                请基于下面 JSON 生成 1 份 %s 计划。
                要求：
                1. 计划必须是 %s，策略是：%s
                2. 每份计划 3-6 个步骤。
                3. itemType 只能是 EVENT、CHALLENGE、STUDY、REFLECTION。
                4. EVENT 步骤的 eventId 必须来自 candidateEvents；其他步骤 eventId 为 null。
                5. scheduleHint 要写成适合前端展示的短文本，例如“第2周周末”。
                6. 至少安排一个 REFLECTION 步骤，把行动转化为日志或成长记录。
                7. 如果当前日程较满，要在 warnings 中提醒。

                后端数据 JSON：
                %s
                """.formatted(style, style, strategy, toJson(context));

        Optional<AiDtos.PlanRecommendationModelResponse> response = llmClient.chatForJson(
                systemPrompt,
                userPrompt,
                AiDtos.PlanRecommendationModelResponse.class
        );
        return response
                .map(output -> normalizePlans(output, evidence.retrievedEvents(), agentName, style))
                .flatMap(plans -> plans.stream().findFirst());
    }

    private List<AiDtos.RecommendedPlan> normalizePlans(AiDtos.PlanRecommendationModelResponse output,
                                                        List<RetrievedEvent> retrievedEvents,
                                                        String agentName,
                                                        String fallbackStyle) {
        if (output.plans() == null) {
            return List.of();
        }

        Set<Long> validEventIds = retrievedEvents.stream()
                .map(item -> item.event().getId())
                .collect(Collectors.toSet());

        return output.plans().stream()
                .filter(plan -> plan != null && plan.steps() != null && !plan.steps().isEmpty())
                .map(plan -> {
                    List<AiDtos.PlanStep> steps = plan.steps().stream()
                                .filter(step -> step != null)
                                .filter(step -> step.eventId() == null || validEventIds.contains(step.eventId()))
                                .sorted(Comparator.comparingInt(step -> step.order() <= 0 ? Integer.MAX_VALUE : step.order()))
                                .limit(6)
                                .map(step -> new AiDtos.PlanStep(
                                        Math.max(step.order(), 1),
                                        cleanText(step.dateLabel(), "近期", 40),
                                        cleanText(step.title(), "行动步骤", 100),
                                        cleanText(step.itemType(), "STUDY", 24),
                                        step.eventId(),
                                        cleanText(step.scheduleHint(), "自行安排", 80),
                                        cleanText(step.reason(), "与当前目标相关。", 160)
                                ))
                                .toList();
                    List<String> trace = new ArrayList<>(cleanList(plan.agentTrace(), List.of(), 4, 120));
                    trace.add(agentName + ": 生成 " + fallbackStyle + " 计划");
                    return buildPlan(
                            cleanText(plan.title(), fallbackStyle + "成长行动计划", 80),
                            cleanText(plan.style(), fallbackStyle, 40),
                            cleanText(plan.summary(), "根据目标、画像和候选活动生成的行动计划。", 240),
                            steps,
                            cleanList(plan.warnings(), List.of(), 4, 120),
                            clamp(plan.qualityScore() == null ? 72 : plan.qualityScore(), 1, 100),
                            trace
                    );
                })
                .filter(plan -> !plan.steps().isEmpty())
                .limit(3)
                .toList();
    }

    private List<AiDtos.RecommendedPlan> fallbackPlans(AiDtos.PlanRecommendationRequest request,
                                                       int horizonDays,
                                                       UserMemoryDtos.Profile memory,
                                                       List<RetrievedEvent> retrievedEvents,
                                                       List<ScheduleDtos.ScheduleItemResponse> schedule) {
        List<EventEntity> events = retrievedEvents.stream().map(RetrievedEvent::event).limit(5).toList();
        List<String> warnings = schedule.size() >= 8
                ? List.of("当前日程已有较多安排，建议先选择稳妥型计划。")
                : List.of("计划为本地规则生成，可继续用 AI 细化。");

        return List.of(
                buildFallbackPlan("稳妥型计划", "稳妥", request.goal(), horizonDays, events, warnings, false),
                buildFallbackPlan("集中突破计划", "集中", request.goal(), horizonDays, events, warnings, true),
                buildFallbackPlan("探索型计划", "探索", request.goal(), horizonDays, events, warnings, false)
        );
    }

    private AiDtos.RecommendedPlan buildFallbackPlan(String title,
                                                     String style,
                                                     String goal,
                                                     int horizonDays,
                                                     List<EventEntity> events,
                                                     List<String> warnings,
                                                     boolean intense) {
        List<AiDtos.PlanStep> steps = new ArrayList<>();
        steps.add(new AiDtos.PlanStep(1, "第1周", "明确目标：" + goal, "STUDY", null, "本周任意 30 分钟", "先把目标拆成可以执行的小任务。"));

        int order = 2;
        for (EventEntity event : events.stream().limit(intense ? 3 : 2).toList()) {
            steps.add(new AiDtos.PlanStep(
                    order,
                    "第" + Math.min(order, Math.max(horizonDays / 7, 1)) + "周",
                    "参加：" + event.getTitle(),
                    "EVENT",
                    event.getId(),
                    event.getStartTime().toLocalDate().toString(),
                    "该活动来自混合召回候选，和目标或画像存在匹配。"
            ));
            order += 1;
        }

        steps.add(new AiDtos.PlanStep(order, "计划末尾", "写一次复盘日志", "REFLECTION", null, "活动或学习后当天晚上", "把行动转化为成长记录，供后续推荐和智能简历使用。"));
        return buildPlan(
                title,
                style,
                "围绕“" + goal + "”生成的 " + style + " 方案，周期约 " + horizonDays + " 天。",
                steps,
                warnings,
                intense ? 68 : 72,
                List.of("RuleFallbackPlanner: 模型不可用时用本地规则生成")
        );
    }

    private List<AiDtos.RecommendedPlan> checkAndFormatPlans(List<AiDtos.RecommendedPlan> plans,
                                                             List<RetrievedEvent> retrievedEvents,
                                                             List<ScheduleDtos.ScheduleItemResponse> schedule,
                                                             McpDtos.ToolContextResponse toolContext) {
        Map<Long, RetrievedEvent> retrievedById = retrievedEvents.stream()
                .collect(Collectors.toMap(item -> item.event().getId(), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        return plans.stream()
                .map(plan -> checkAndFormatPlan(plan, retrievedById, schedule, toolContext))
                .filter(plan -> plan.steps() != null && !plan.steps().isEmpty())
                .limit(3)
                .toList();
    }

    private AiDtos.RecommendedPlan checkAndFormatPlan(AiDtos.RecommendedPlan plan,
                                                      Map<Long, RetrievedEvent> retrievedById,
                                                      List<ScheduleDtos.ScheduleItemResponse> schedule,
                                                      McpDtos.ToolContextResponse toolContext) {
        List<String> warnings = new ArrayList<>(cleanList(plan.warnings(), List.of(), 6, 140));
        List<String> trace = new ArrayList<>(cleanList(plan.agentTrace(), List.of(), 8, 140));
        int score = plan.qualityScore() == null ? 72 : clamp(plan.qualityScore(), 1, 100);
        boolean hasReflection = false;
        boolean hasEvent = false;

        for (AiDtos.PlanStep step : plan.steps()) {
            String type = step.itemType() == null ? "STUDY" : step.itemType().trim().toUpperCase(Locale.ROOT);
            if ("REFLECTION".equals(type)) {
                hasReflection = true;
            }
            if ("EVENT".equals(type)) {
                hasEvent = true;
                RetrievedEvent retrieved = step.eventId() == null ? null : retrievedById.get(step.eventId());
                if (retrieved == null) {
                    warnings.add("步骤“" + step.title() + "”引用的活动不在召回证据中。");
                    score -= 18;
                    continue;
                }
                if (retrieved.finalScore() <= 0 || isExpiredForPlan(retrieved.event(), toolContext)) {
                    warnings.add("活动“" + retrieved.event().getTitle() + "”已经过期或检索分为 0，建议替换。");
                    score -= 25;
                }
                if (conflictsWithSchedule(retrieved.event().getStartTime(), retrieved.event().getEndTime(), schedule)) {
                    warnings.add("活动“" + retrieved.event().getTitle() + "”和当前日程存在时间冲突。");
                    score -= 12;
                }
            }
        }

        if (!hasReflection) {
            warnings.add("计划缺少复盘步骤，已建议补充日志沉淀。");
            score -= 6;
        }
        if (!hasEvent && !retrievedById.isEmpty()) {
            warnings.add("计划没有使用召回到的真实活动，可以考虑加入一个低风险活动验证方向。");
            score -= 8;
        }
        if (schedule.size() >= 10) {
            warnings.add("当前日程较满，执行时建议优先保留关键步骤。");
            score -= 5;
        }

        trace.add("ScheduleChecker: 校验 eventId、过期活动、时间冲突和复盘步骤");
        return buildPlan(
                plan.title(),
                plan.style(),
                plan.summary(),
                plan.steps(),
                warnings.stream().distinct().limit(8).toList(),
                clamp(score, 1, 100),
                trace
        );
    }

    private List<AiDtos.RecommendedPlan> critiquePlans(List<AiDtos.RecommendedPlan> plans, PlanEvidencePack evidence) {
        if (!llmClient.isEnabled() || plans.isEmpty()) {
            return plans;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("goalUnderstanding", evidence.goal());
        context.put("candidateEventCount", evidence.retrievedEvents().size());
        context.put("topCandidateEvents", evidence.retrievedEvents().stream()
                .limit(8)
                .map(item -> eventContext(item.event(), item))
                .toList());
        context.put("plans", indexedPlanContext(plans));

        String systemPrompt = """
                你是 do not miss 的 Critic Agent。
                你只负责审核已经生成的计划，不要新增计划，不要编造活动。
                判断标准：是否围绕目标、是否使用真实召回证据、是否可执行、是否有先后顺序、是否包含复盘。
                除非计划明显无关、引用虚假活动或不可执行，否则倾向保留并给出改进建议。
                输出严格 JSON，不要 Markdown。
                JSON 格式：
                {"reviews":[{"planIndex":0,"keep":true,"qualityScore":86,"critique":"审核意见","suggestions":["建议"]}]}
                """;
        String userPrompt = """
                请审核下面计划。planIndex 从 0 开始，对每份计划都给出 review。
                qualityScore 使用 1-100；低于 55 或明显不可执行才 keep=false。
                审核数据 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, AiDtos.PlanCriticModelResponse.class)
                .map(output -> applyPlanCritic(plans, output))
                .filter(reviewed -> !reviewed.isEmpty())
                .orElse(plans);
    }

    private List<AiDtos.RecommendedPlan> applyPlanCritic(List<AiDtos.RecommendedPlan> plans,
                                                         AiDtos.PlanCriticModelResponse output) {
        if (output.reviews() == null || output.reviews().isEmpty()) {
            return plans;
        }
        Map<Integer, AiDtos.PlanCriticItem> reviews = output.reviews().stream()
                .filter(item -> item != null && item.planIndex() != null)
                .collect(Collectors.toMap(AiDtos.PlanCriticItem::planIndex, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<AiDtos.RecommendedPlan> reviewed = new ArrayList<>();
        for (int index = 0; index < plans.size(); index += 1) {
            AiDtos.RecommendedPlan plan = plans.get(index);
            AiDtos.PlanCriticItem review = reviews.get(index);
            if (review == null) {
                reviewed.add(plan);
                continue;
            }
            int score = clamp(review.qualityScore() == null ? plan.qualityScore() == null ? 70 : plan.qualityScore() : review.qualityScore(), 1, 100);
            if (Boolean.FALSE.equals(review.keep()) && score < 55) {
                continue;
            }
            List<String> warnings = new ArrayList<>(cleanList(plan.warnings(), List.of(), 8, 140));
            if (review.critique() != null && !review.critique().isBlank()) {
                warnings.add("Critic：" + cleanText(review.critique(), "", 120));
            }
            cleanList(review.suggestions(), List.of(), 3, 100)
                    .forEach(suggestion -> warnings.add("建议：" + suggestion));

            List<String> trace = new ArrayList<>(cleanList(plan.agentTrace(), List.of(), 8, 140));
            trace.add("CriticAgent: 质量分 " + score);
            reviewed.add(buildPlan(
                    plan.title(),
                    plan.style(),
                    plan.summary(),
                    plan.steps(),
                    warnings.stream().distinct().limit(8).toList(),
                    score,
                    trace
            ));
        }
        return reviewed.stream()
                .sorted(Comparator.comparing(plan -> plan.qualityScore() == null ? 0 : plan.qualityScore(), Comparator.reverseOrder()))
                .limit(3)
                .toList();
    }

    private AiDtos.RecommendedPlan buildPlan(String title,
                                             String style,
                                             String summary,
                                             List<AiDtos.PlanStep> steps,
                                             List<String> warnings,
                                             Integer qualityScore,
                                             List<String> agentTrace) {
        List<AiDtos.PlanStep> normalizedSteps = normalizePlanSteps(steps);
        return new AiDtos.RecommendedPlan(
                title,
                style,
                summary,
                normalizedSteps,
                warnings == null ? List.of() : warnings,
                qualityScore,
                agentTrace == null ? List.of() : agentTrace,
                buildPlanNodes(normalizedSteps, agentTrace),
                buildPlanEdges(normalizedSteps),
                buildScheduleDrafts(normalizedSteps)
        );
    }

    private List<AiDtos.PlanStep> normalizePlanSteps(List<AiDtos.PlanStep> steps) {
        if (steps == null) {
            return List.of();
        }
        List<AiDtos.PlanStep> normalized = new ArrayList<>();
        int order = 1;
        for (AiDtos.PlanStep step : steps.stream()
                .filter(item -> item != null && item.title() != null && !item.title().isBlank())
                .sorted(Comparator.comparingInt(item -> item.order() <= 0 ? Integer.MAX_VALUE : item.order()))
                .limit(6)
                .toList()) {
            normalized.add(new AiDtos.PlanStep(
                    order,
                    cleanText(step.dateLabel(), "近期", 40),
                    cleanText(step.title(), "行动步骤", 100),
                    cleanText(step.itemType(), "STUDY", 24).toUpperCase(Locale.ROOT),
                    step.eventId(),
                    cleanText(step.scheduleHint(), "自行安排", 80),
                    cleanText(step.reason(), "与当前目标相关。", 160)
            ));
            order += 1;
        }
        return normalized;
    }

    private List<AiDtos.PlanNode> buildPlanNodes(List<AiDtos.PlanStep> steps, List<String> agentTrace) {
        String agent = agentTrace == null || agentTrace.isEmpty() ? "" : agentTrace.get(0).split(":")[0];
        return steps.stream()
                .map(step -> new AiDtos.PlanNode(
                        planNodeId(step),
                        step.itemType(),
                        step.title(),
                        step.dateLabel() + " · " + step.scheduleHint(),
                        step.eventId(),
                        step.order(),
                        agent
                ))
                .toList();
    }

    private List<AiDtos.PlanEdge> buildPlanEdges(List<AiDtos.PlanStep> steps) {
        List<AiDtos.PlanEdge> edges = new ArrayList<>();
        for (int i = 1; i < steps.size(); i += 1) {
            edges.add(new AiDtos.PlanEdge(
                    planNodeId(steps.get(i - 1)),
                    planNodeId(steps.get(i)),
                    "next"
            ));
        }
        return edges;
    }

    private List<AiDtos.ScheduleDraft> buildScheduleDrafts(List<AiDtos.PlanStep> steps) {
        return steps.stream()
                .map(step -> new AiDtos.ScheduleDraft(
                        step.title(),
                        step.itemType(),
                        step.eventId(),
                        step.dateLabel(),
                        step.scheduleHint(),
                        step.reason()
                ))
                .toList();
    }

    private String planNodeId(AiDtos.PlanStep step) {
        if (step.eventId() != null) {
            return "event-" + step.eventId();
        }
        return "step-" + step.order();
    }

    private List<Map<String, Object>> indexedPlanContext(List<AiDtos.RecommendedPlan> plans) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < plans.size(); i += 1) {
            AiDtos.RecommendedPlan plan = plans.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("planIndex", i);
            item.put("title", plan.title());
            item.put("style", plan.style());
            item.put("summary", plan.summary());
            item.put("qualityScore", plan.qualityScore());
            item.put("warnings", plan.warnings());
            item.put("steps", plan.steps());
            item.put("agentTrace", plan.agentTrace());
            data.add(item);
        }
        return data;
    }

    private boolean conflictsWithSchedule(LocalDateTime startTime,
                                          LocalDateTime endTime,
                                          List<ScheduleDtos.ScheduleItemResponse> schedule) {
        if (startTime == null || endTime == null) {
            return false;
        }
        return schedule.stream()
                .filter(item -> item.startTime() != null && item.endTime() != null)
                .anyMatch(item -> startTime.isBefore(item.endTime()) && endTime.isAfter(item.startTime()));
    }

    private boolean isExpiredForPlan(EventEntity event, McpDtos.ToolContextResponse toolContext) {
        if (event == null || toolContext == null || toolContext.currentTime() == null) {
            return false;
        }
        LocalDateTime now = toolContext.currentTime().serverTime().toLocalDateTime();
        LocalDateTime endTime = event.getEndTime() == null ? event.getStartTime() : event.getEndTime();
        return endTime != null && endTime.isBefore(now);
    }

    private Optional<List<AiDtos.RecommendedEvent>> recommendEventsWithModel(List<EventEntity> candidates,
                                                                            List<AchievementRecordEntity> history,
                                                                      UserMemoryDtos.Profile memory,
                                                                      Map<Long, RetrievedEvent> retrievedById,
                                                                      AiDtos.EventRecommendationRequest request,
                                                                      RetrievalDtos.QueryRewrite query,
                                                                      McpDtos.ToolContextResponse toolContext) {
        if (!llmClient.isEnabled() || candidates.isEmpty()) {
            return Optional.empty();
        }

        List<EventEntity> modelCandidates = candidates.stream()
                .limit(MAX_MODEL_EVENTS)
                .toList();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("studentNeed", request.need());
        Map<String, Object> queryContext = new LinkedHashMap<>();
        queryContext.put("mode", query.mode());
        queryContext.put("rewrittenQuery", query.rewrittenQuery());
        queryContext.put("goal", query.goal());
        queryContext.put("level", query.level());
        queryContext.put("intentTags", query.intentTags());
        queryContext.put("skills", query.skills());
        queryContext.put("preferredCategories", query.preferredCategories());
        queryContext.put("preferredLocation", query.preferredLocation());
        queryContext.put("benefitPreference", query.benefitPreference());
        queryContext.put("constraints", query.constraints());
        queryContext.put("evidence", query.evidence());
        context.put("queryUnderstanding", queryContext);
        context.put("filters", Map.of(
                "category", nullToEmpty(request.category()),
                "benefitType", nullToEmpty(request.benefitType()),
                "location", nullToEmpty(request.location())
        ));
        context.put("mcpToolContext", mcpToolContextService.promptContext(toolContext));
        context.put("studentMemory", memory);
        context.put("candidateEvents", modelCandidates.stream().map(event -> eventContext(event, retrievedById.get(event.getId()))).toList());
        context.put("studentHistory", history.stream().limit(12).map(this::historyContext).toList());

        String systemPrompt = """
                你是 do not miss 的学生成长机会推荐 Agent。
                你可以参考 mcpToolContext 中的 currentTime 和 location 来判断当前时间、周几、学生大致位置和活动时效性。
                你只能基于后端给出的 candidateEvents 推荐，不能编造新的活动、组织、时间或地点。
                请优先保留与学生目标存在明确关键词、技能、类别或 semanticScore 语义证据的候选；不要只返回最完美的一两个。
                中等相关但能帮助学生探索方向的活动可以返回，并在理由里说明适合点和可能的边界。
                输出严格 JSON，不要输出 Markdown 或解释性前后缀。
                JSON 格式：
                {
                  "recommendations": [
                    {"eventId": 1, "score": 88, "reason": "推荐理由", "evidence": ["匹配证据1", "匹配证据2"]}
                  ]
                }
                """;

        String userPrompt = """
                请根据下面 JSON 数据完成事件推荐排序。
                要求：
                1. 最多返回 5 个推荐。
                2. eventId 必须来自 candidateEvents。
                3. score 使用 1-100 的整数，低于 55 的活动不要返回。
                4. reason 用中文，控制在 100 字以内，说明为什么适合该学生。
                5. evidence 必须引用候选事件或学生画像中的具体字段，例如技能、地点、收益、类别、历史经历。
                6. 如果 semanticScore、bm25Score 或 finalScore 较高，即使不是完全命中，也可以作为探索型推荐保留。

                后端数据 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, AiDtos.EventRecommendationModelResponse.class)
                .map(output -> normalizeModelRecommendations(output, modelCandidates, request.need(), textOfHistory(history), memory, retrievedById))
                .map(recommendations -> critiqueRecommendations(recommendations, request.need(), memory))
                .map(recommendations -> recommendations.stream().limit(MAX_RECOMMENDATIONS).toList());
    }

    private List<AiDtos.RecommendedEvent> normalizeModelRecommendations(AiDtos.EventRecommendationModelResponse output,
                                                                        List<EventEntity> candidates,
                                                                        String need,
                                                                        String historyText,
                                                                        UserMemoryDtos.Profile memory,
                                                                        Map<Long, RetrievedEvent> retrievedById) {
        if (output.recommendations() == null) {
            return List.of();
        }

        Map<Long, EventEntity> candidateById = candidates.stream()
                .collect(Collectors.toMap(EventEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Set<Long> usedIds = new LinkedHashSet<>();
        List<AiDtos.RecommendedEvent> recommendations = new ArrayList<>();

        for (AiDtos.EventRecommendationModelItem item : output.recommendations()) {
            if (item == null || item.eventId() == null || usedIds.contains(item.eventId())) {
                continue;
            }

            EventEntity event = candidateById.get(item.eventId());
            if (event == null) {
                continue;
            }

            int score = clamp(item.score() == null ? 50 : item.score(), 1, 100);
            List<String> evidence = mergeEvidence(item.evidence(), buildEvidence(event, need, historyText, memory, retrievedById));
            if (score < MIN_MODEL_SCORE || evidence.isEmpty()) {
                continue;
            }

            usedIds.add(item.eventId());
            recommendations.add(new AiDtos.RecommendedEvent(
                    EventDtos.EventResponse.from(event),
                    score,
                    confidenceLabel(score),
                    evidence,
                    cleanText(item.reason(), buildDefaultReason(event, evidence), 140)
            ));

            if (recommendations.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
        }

        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            for (EventEntity event : candidates) {
                if (usedIds.contains(event.getId())) {
                    continue;
                }
                RetrievedEvent retrievedEvent = retrievedById.get(event.getId());
                if (retrievedEvent == null || retrievedEvent.finalScore() < MIN_MODEL_SCORE) {
                    continue;
                }
                List<String> evidence = buildEvidence(event, need, historyText, memory, retrievedById);
                if (evidence.isEmpty()) {
                    continue;
                }
                int score = clamp((int) Math.round(retrievedEvent.finalScore()), MIN_MODEL_SCORE, 100);
                usedIds.add(event.getId());
                recommendations.add(new AiDtos.RecommendedEvent(
                        EventDtos.EventResponse.from(event),
                        score,
                        confidenceLabel(score),
                        evidence,
                        "混合检索认为该活动与需求存在较强关键词或语义证据，作为补充推荐保留。"
                ));
                if (recommendations.size() >= MAX_RECOMMENDATIONS) {
                    break;
                }
            }
        }

        return recommendations;
    }

    private List<AiDtos.RecommendedEvent> critiqueRecommendations(List<AiDtos.RecommendedEvent> recommendations,
                                                                  String need,
                                                                  UserMemoryDtos.Profile memory) {
        if (recommendations.isEmpty() || !llmClient.isEnabled()) {
            return recommendations;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("studentNeed", need);
        context.put("studentMemory", memory);
        context.put("recommendations", recommendations.stream().map(recommendation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", recommendation.event().id());
            item.put("title", recommendation.event().title());
            item.put("category", recommendation.event().category());
            item.put("location", recommendation.event().location());
            item.put("skill", recommendation.event().skill());
            item.put("moneyAmount", recommendation.event().moneyAmount());
            item.put("score", recommendation.score());
            item.put("reason", recommendation.reason());
            item.put("evidence", recommendation.evidence());
            return item;
        }).toList());

        String systemPrompt = """
                你是推荐结果 Critic Agent，负责过滤牵强推荐。
                你只审查已有推荐，不添加新活动。
                只有活动与学生需求明显无关、已过期导致 finalScore 为 0、或缺乏任何关键词/语义/结构化证据时，才 keep=false。
                对于存在 semanticScore、bm25Score 或明确技能匹配的中等相关活动，应倾向 keep=true。
                输出严格 JSON，不要输出 Markdown。
                JSON 格式：
                {"reviews":[{"eventId":1,"keep":true,"adjustedScore":82,"critique":"审核理由"}]}
                """;

        String userPrompt = """
                请审核下面推荐结果。
                要求：
                1. adjustedScore 仍然使用 1-100。
                2. keep=false 的项目会被后端过滤。
                3. 低于 55 分的项目应设置 keep=false。
                4. critique 用中文，说明保留或过滤理由，控制在 80 字以内。

                推荐数据 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, AiDtos.EventRecommendationCriticResponse.class)
                .map(output -> applyCriticReviews(recommendations, output))
                .filter(reviewed -> !reviewed.isEmpty())
                .orElse(recommendations);
    }

    private List<AiDtos.RecommendedEvent> applyCriticReviews(List<AiDtos.RecommendedEvent> recommendations,
                                                             AiDtos.EventRecommendationCriticResponse output) {
        if (output.reviews() == null || output.reviews().isEmpty()) {
            return recommendations;
        }

        Map<Long, AiDtos.RecommendedEvent> byId = recommendations.stream()
                .collect(Collectors.toMap(item -> item.event().id(), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<AiDtos.RecommendedEvent> reviewed = new ArrayList<>();
        Set<Long> reviewedIds = new LinkedHashSet<>();

        for (AiDtos.EventRecommendationCriticItem item : output.reviews()) {
            if (item != null && item.eventId() != null) {
                reviewedIds.add(item.eventId());
            }
            if (item == null || item.eventId() == null || !Boolean.TRUE.equals(item.keep())) {
                continue;
            }

            AiDtos.RecommendedEvent original = byId.get(item.eventId());
            if (original == null) {
                continue;
            }

            int score = clamp(item.adjustedScore() == null ? original.score() : item.adjustedScore(), 1, 100);
            if (score < MIN_MODEL_SCORE) {
                continue;
            }

            String reason = original.reason();
            if (item.critique() != null && !item.critique().isBlank()) {
                reason = reason + " Critic: " + cleanText(item.critique(), "", 100);
            }

            reviewed.add(new AiDtos.RecommendedEvent(
                    original.event(),
                    score,
                    confidenceLabel(score),
                    original.evidence(),
                    reason
            ));
        }

        for (AiDtos.RecommendedEvent recommendation : recommendations) {
            if (!reviewedIds.contains(recommendation.event().id())) {
                reviewed.add(recommendation);
            }
        }

        return reviewed.stream()
                .sorted(Comparator.comparingInt(AiDtos.RecommendedEvent::score).reversed())
                .limit(MAX_RECOMMENDATIONS)
                .toList();
    }

    private List<AiDtos.RecommendedEvent> rankEventsByRules(List<EventEntity> candidates,
                                                            String need,
                                                            String historyText,
                                                            UserMemoryDtos.Profile memory,
                                                            Map<Long, RetrievedEvent> retrievedById) {
        return candidates.stream()
                .map(event -> ruleMatch(event, need, historyText, memory))
                .filter(match -> match.score() >= MIN_RULE_SCORE)
                .sorted(Comparator.comparingInt(RuleMatch::score).reversed())
                .limit(MAX_RECOMMENDATIONS)
                .map(match -> new AiDtos.RecommendedEvent(
                        EventDtos.EventResponse.from(match.event()),
                        match.score(),
                        confidenceLabel(match.score()),
                        mergeEvidence(match.evidence(), retrievedEvidence(match.event(), retrievedById)),
                        buildDefaultReason(match.event(), mergeEvidence(match.evidence(), retrievedEvidence(match.event(), retrievedById)))
                ))
                .toList();
    }

    private RuleMatch ruleMatch(EventEntity event, String need, String historyText, UserMemoryDtos.Profile memory) {
        String eventText = textOfEvent(event).toLowerCase(Locale.ROOT);
        String normalizedNeed = nullToEmpty(need).toLowerCase(Locale.ROOT);
        int rawScore = 0;
        List<String> evidence = new ArrayList<>();

        for (String term : normalizedNeed.split("[\\s,，;；、]+")) {
            if (!term.isBlank() && eventText.contains(term)) {
                rawScore += term.length() >= 2 ? 3 : 1;
                evidence.add("需求关键词匹配：" + term);
            }
        }

        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("日语", "翻译", "中日"), "语言沟通相关");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("英语", "商务英语"), "英语能力相关");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("锻炼", "运动", "身体", "健身"), "身体/运动目标相关");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("报酬", "金钱", "兼职", "有偿"), "包含报酬诉求");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("研究", "调研", "访谈", "问卷", "数据"), "研究调研相关");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("运营", "现场", "活动", "执行"), "活动执行相关");
        rawScore += scoreIntent(normalizedNeed, eventText, evidence, List.of("线上", "远程", "在家"), "参与方式灵活");

        rawScore += scoreIntent(normalizedNeed, eventText, evidence,
                List.of("java", "spring", "spring boot", "后端", "编程", "程序设计", "项目开发", "外卖", "微信"),
                "Java/编程项目相关");

        if (memory.preferredCategories().contains(event.getCategory().label())) {
            rawScore += 2;
            evidence.add("与历史高频类别相关：" + event.getCategory().label());
        }
        if (memory.preferredLocations().stream().anyMatch(location -> event.getLocation().contains(location) || location.contains(event.getLocation()))) {
            rawScore += 2;
            evidence.add("地点与历史参与地点接近");
        }
        if (historyText.contains(event.getCategory().label())) {
            rawScore += 1;
            evidence.add("与已有经历方向有连续性");
        }

        int score = rawScore <= 0 ? 0 : Math.min(92, 43 + rawScore * 7);
        return new RuleMatch(event, score, evidence.stream().distinct().limit(5).toList());
    }

    @Transactional(readOnly = true)
    public AiDtos.SelfAnalysisResponse selfAnalysis(String userId) {
        List<AchievementRecordEntity> history = achievementService.historyEntities(userId);
        AchievementDtos.AchievementSummary summary = achievementService.summary(userId);
        UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
        ResumeEvidencePack resumeEvidence = buildResumeEvidencePack(userId);

        if (history.isEmpty()) {
            return emptySelfAnalysis(summary, fallbackMode());
        }

        Optional<AiDtos.SelfAnalysisResponse> modelAnalysis = selfAnalysisWithModel(history, summary, memory, resumeEvidence);
        return modelAnalysis.orElseGet(() -> ruleBasedSelfAnalysis(history, summary, memory, resumeEvidence, fallbackMode()));
    }

    private Optional<AiDtos.SelfAnalysisResponse> selfAnalysisWithModel(List<AchievementRecordEntity> history,
                                                                        AchievementDtos.AchievementSummary summary,
                                                                        UserMemoryDtos.Profile memory,
                                                                        ResumeEvidencePack resumeEvidence) {
        if (!llmClient.isEnabled()) {
            return Optional.empty();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("achievementSummary", summary);
        context.put("studentMemory", memory);
        context.put("resumeEvidence", resumeEvidence);
        context.put("history", history.stream().limit(15).map(this::historyContext).toList());

        String systemPrompt = """
                你是 do not miss 的成长分析 Agent。
                你根据学生真实完成记录、结构化画像、能力标签和里程碑证据生成智能简历与成长分析。
                不能编造后端数据中不存在的经历、组织、项目数量或获奖情况。
                resumeEvidence 中的 topTags、milestoneEvidence 和 recentEvidence 是最可靠的简历证据，应优先使用。
                统计图和成长曲线由后端提供，你只负责文字总结、简历要点、优势标签和建议。
                输出严格 JSON，不要输出 Markdown。
                JSON 格式：
                {
                  "summary": "一段成长总结",
                  "resumeBullets": ["简历要点"],
                  "strengths": ["优势1"],
                  "suggestions": ["建议1"]
                }
                """;

        String userPrompt = """
                请基于下面 JSON 数据生成学生成长分析。
                要求：
                1. summary 写 120-220 字，像智能简历的个人经历概括。
                2. resumeBullets 返回 3 条，适合放进简历；优先引用高分能力标签、重要里程碑和用户复盘内容。
                3. strengths 返回 3-5 个能力标签，优先使用 resumeEvidence.topTags 里的标签名。
                4. suggestions 返回 2-4 条下一步成长建议。
                5. 所有数字必须来自 achievementSummary 或 resumeEvidence，不要自行估算。
                6. 如果没有 milestoneEvidence，就使用 topTags 和 recentEvidence，不要声称用户有获奖、证书或项目上线。

                后端数据 JSON：
                %s
                """.formatted(toJson(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, AiDtos.SelfAnalysisModelResponse.class)
                .flatMap(output -> normalizeSelfAnalysis(output, summary));
    }

    private Optional<AiDtos.SelfAnalysisResponse> normalizeSelfAnalysis(AiDtos.SelfAnalysisModelResponse output,
                                                                        AchievementDtos.AchievementSummary summary) {
        if (output.summary() == null || output.summary().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new AiDtos.SelfAnalysisResponse(
                llmClient.modeLabel(),
                cleanText(output.summary(), "已根据历史记录生成成长总结。", 400),
                cleanList(output.resumeBullets(), List.of("完成多个实践项目，并能持续复盘个人成长。"), 3, 160),
                cleanList(output.strengths(), List.of("主动参与", "持续复盘"), 5, 40),
                cleanList(output.suggestions(), List.of("继续补充历史记录中的具体行动和收获。"), 4, 120),
                summary
        ));
    }

    private ResumeEvidencePack buildResumeEvidencePack(String userId) {
        List<ResumeTagEvidence> topTags = growthTagRepository.findByUserIdOrderByScoreDescLastUpdatedAtDesc(userId).stream()
                .sorted(Comparator.comparingInt(GrowthTagEntity::getImportanceScore).reversed()
                        .thenComparing(Comparator.comparingInt(GrowthTagEntity::getScore).reversed())
                        .thenComparing(Comparator.comparingInt(GrowthTagEntity::getEvidenceCount).reversed()))
                .limit(8)
                .map(tag -> new ResumeTagEvidence(
                        tag.getName(),
                        tag.getDescription(),
                        tag.getScore(),
                        tag.getEvidenceCount(),
                        tag.getImportanceScore()
                ))
                .toList();

        List<GrowthTagEvidenceEntity> evidences = growthTagEvidenceRepository.findByUserIdOrderByOccurredAtDesc(userId);
        List<ResumeMilestoneEvidence> milestoneEvidence = evidences.stream()
                .filter(GrowthTagEvidenceEntity::isMilestone)
                .sorted(Comparator.comparing(GrowthTagEvidenceEntity::getOccurredAt).reversed())
                .limit(8)
                .map(this::resumeMilestoneEvidence)
                .toList();
        List<ResumeMilestoneEvidence> recentEvidence = evidences.stream()
                .sorted(Comparator.comparing(GrowthTagEvidenceEntity::getOccurredAt).reversed())
                .limit(8)
                .map(this::resumeMilestoneEvidence)
                .toList();

        return new ResumeEvidencePack(topTags, milestoneEvidence, recentEvidence);
    }

    private ResumeMilestoneEvidence resumeMilestoneEvidence(GrowthTagEvidenceEntity evidence) {
        GrowthTagEntity tag = evidence.getTag();
        return new ResumeMilestoneEvidence(
                evidence.getTitle(),
                tag == null ? "" : tag.getName(),
                evidence.getSummary(),
                evidence.getDid(),
                evidence.getLearned(),
                evidence.getScoreDelta(),
                evidence.isMilestone(),
                evidence.getMilestoneReason(),
                evidence.getOccurredAt() == null ? "" : evidence.getOccurredAt().toString()
        );
    }

    private AiDtos.SelfAnalysisResponse emptySelfAnalysis(AchievementDtos.AchievementSummary summary, String mode) {
        return new AiDtos.SelfAnalysisResponse(
                mode,
                "还没有完成活动。完成活动或挑战，并在历史记录里补充做了什么、学到了什么之后，系统会生成更像简历的成长总结。",
                List.of("暂无可展示经历。"),
                List.of("等待实践数据"),
                List.of("先预约并完成一个适合自己的活动，或者创建一个个人挑战。"),
                summary
        );
    }

    private AiDtos.SelfAnalysisResponse ruleBasedSelfAnalysis(List<AchievementRecordEntity> history,
                                                              AchievementDtos.AchievementSummary summary,
                                                              UserMemoryDtos.Profile memory,
                                                              ResumeEvidencePack resumeEvidence,
                                                              String mode) {
        AchievementRecordEntity latest = history.get(0);
        List<String> topCategories = summary.categoryCounts().stream()
                .limit(3)
                .map(AchievementDtos.CategoryCount::category)
                .toList();
        List<String> tagNames = resumeEvidence.topTags().stream()
                .map(ResumeTagEvidence::name)
                .filter(value -> value != null && !value.isBlank())
                .limit(5)
                .toList();
        List<String> keywords = !tagNames.isEmpty()
                ? tagNames
                : memory.evidenceKeywords().isEmpty()
                ? List.of("主动参与", "持续复盘")
                : memory.evidenceKeywords().stream().limit(5).toList();
        Optional<ResumeMilestoneEvidence> primaryMilestone = resumeEvidence.milestoneEvidence().stream().findFirst()
                .or(() -> resumeEvidence.recentEvidence().stream().findFirst());
        String primaryEvidence = primaryMilestone
                .map(evidence -> evidence.title() + "（" + evidence.tagName() + "，+" + evidence.scoreDelta() + " 分）")
                .orElse(latest.getEventTitle());
        String topTagSummary = resumeEvidence.topTags().isEmpty()
                ? String.join("、", keywords)
                : resumeEvidence.topTags().stream()
                .limit(3)
                .map(tag -> tag.name() + "（" + tag.score() + " 分，" + tag.evidenceCount() + " 条证据）")
                .collect(Collectors.joining("、"));

        String summaryText = "该学生已完成 " + summary.completedCount() + " 个实践项目或个人挑战，覆盖 "
                + summary.categoryCount() + " 类方向，主要经历集中在 "
                + String.join("、", topCategories) + "。从能力标签和里程碑证据看，学生正在积累 "
                + topTagSummary + " 等能力，代表证据是“" + primaryEvidence + "”。";

        List<String> resumeBullets = List.of(
                "完成 " + summary.completedCount() + " 个社会实践或个人挑战，沉淀出 " + String.join("、", keywords.stream().limit(3).toList()) + " 等能力标签。",
                primaryMilestone
                        .map(evidence -> "关键证据：“" + evidence.title() + "”支撑 " + evidence.tagName() + " 能力，复盘内容包括 " + compact(firstPresent(evidence.did(), evidence.learned(), evidence.summary()), 90) + "。")
                        .orElse("代表经历：" + latest.getOrganizationName() + " 的“" + latest.getEventTitle() + "”，参与内容包括 " + compact(firstPresent(latest.getDid(), latest.getContent()), 80) + "。"),
                resumeEvidence.topTags().isEmpty()
                        ? "能力关键词：" + String.join("、", keywords) + "。"
                        : "能力证据：" + topTagSummary + "。"
        );

        return new AiDtos.SelfAnalysisResponse(
                mode,
                summaryText,
                resumeBullets,
                keywords,
                buildSuggestions(summary),
                summary
        );
    }

    private List<String> buildSuggestions(AchievementDtos.AchievementSummary summary) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("继续补充历史记录里的“做了什么”和“学到了什么”，AI 简历会更具体。");
        if (summary.categoryCount() < 3) {
            suggestions.add("尝试更多类别的实践，避免成长画像过于单一。");
        }
        suggestions.add("下一步可以选择和现有优势相邻但难度更高的活动或挑战。");
        return suggestions;
    }

    private int scoreIntent(String need, String eventText, List<String> evidence, List<String> words, String label) {
        boolean needMatched = words.stream().anyMatch(need::contains);
        boolean eventMatched = words.stream().anyMatch(eventText::contains);
        if (needMatched && eventMatched) {
            evidence.add(label);
            return 4;
        }
        return 0;
    }

    private List<String> buildEvidence(EventEntity event,
                                       String need,
                                       String historyText,
                                       UserMemoryDtos.Profile memory,
                                       Map<Long, RetrievedEvent> retrievedById) {
        return mergeEvidence(ruleMatch(event, need, historyText, memory).evidence(), retrievedEvidence(event, retrievedById));
    }

    private List<String> retrievedEvidence(EventEntity event, Map<Long, RetrievedEvent> retrievedById) {
        RetrievedEvent retrievedEvent = retrievedById.get(event.getId());
        if (retrievedEvent == null) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>(retrievedEvent.evidence());
        evidence.add("混合召回分：" + retrievedEvent.finalScore());
        return evidence;
    }

    private List<String> mergeEvidence(List<String> modelEvidence, List<String> backendEvidence) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (modelEvidence != null) {
            modelEvidence.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> cleanText(value, "", 80))
                    .forEach(merged::add);
        }
        if (backendEvidence != null) {
            backendEvidence.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(merged::add);
        }
        return merged.stream().limit(5).toList();
    }

    private String buildDefaultReason(EventEntity event, List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "该活动与当前需求有一定相关性，但建议继续补充需求以获得更准确推荐。";
        }
        return "匹配点：" + String.join("；", evidence.stream().limit(3).toList()) + "。";
    }

    private String buildRecommendationMessage(List<AiDtos.RecommendedEvent> recommendations, int candidateCount) {
        if (recommendations.isEmpty()) {
            return "已检查 " + candidateCount + " 个候选活动，但没有找到足够匹配的结果。可以放宽条件，或创建个人挑战补齐目标。";
        }
        if (recommendations.size() < Math.min(candidateCount, MAX_RECOMMENDATIONS)) {
            return "已过滤牵强活动，只返回 " + recommendations.size() + " 个高相关结果。";
        }
        return "已按需求、学生画像、活动证据和 MCP 工具上下文完成排序。";
    }

    private String confidenceLabel(int score) {
        if (score >= 85) {
            return "HIGH";
        }
        if (score >= 70) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private Map<String, Object> eventContext(EventEntity event) {
        return eventContext(event, null);
    }

    private Map<String, Object> eventContext(EventEntity event, RetrievedEvent retrievedEvent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.getId());
        data.put("title", event.getTitle());
        data.put("organizationName", event.getOrganizationName());
        data.put("category", event.getCategory().label());
        data.put("categoryCode", event.getCategory().name());
        data.put("startTime", event.getStartTime());
        data.put("location", event.getLocation());
        data.put("content", compact(event.getContent(), 260));
        data.put("benefitType", event.getBenefitType().label());
        data.put("benefitTypeCode", event.getBenefitType().name());
        data.put("skill", compact(event.getSkill(), 180));
        data.put("moneyAmount", event.getMoneyAmount());
        data.put("compressedContext", compressedEventContext(event, retrievedEvent));
        if (retrievedEvent != null) {
            data.put("retrieval", Map.of(
                    "keywordScore", retrievedEvent.keywordScore(),
                    "bm25Score", retrievedEvent.bm25Score(),
                    "semanticScore", retrievedEvent.semanticScore(),
                    "intentScore", retrievedEvent.intentScore(),
                    "memoryScore", retrievedEvent.memoryScore(),
                    "metadataScore", retrievedEvent.metadataScore(),
                    "recallScore", retrievedEvent.recallScore(),
                    "finalScore", retrievedEvent.finalScore(),
                    "evidence", retrievedEvent.evidence()
            ));
        }
        return data;
    }

    private Map<String, Object> compressedEventContext(EventEntity event, RetrievedEvent retrievedEvent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", compact(String.join(" ",
                event.getTitle(),
                event.getOrganizationName(),
                event.getCategory().label(),
                event.getLocation(),
                event.getContent(),
                nullToEmpty(event.getSkill())
        ), 360));
        data.put("keptFields", List.of("title", "organizationName", "category", "startTime", "location", "benefitType", "skill", "retrievalEvidence"));
        data.put("evidence", retrievedEvent == null ? List.of() : retrievedEvent.evidence().stream().limit(4).toList());
        return data;
    }

    private Map<String, Object> historyContext(AchievementRecordEntity record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceType", record.getSourceType().name());
        data.put("title", record.getEventTitle());
        data.put("organizationName", record.getOrganizationName());
        data.put("category", record.getCategory());
        data.put("location", record.getLocation());
        data.put("content", compact(record.getContent(), 420));
        data.put("skill", compact(record.getSkill(), 220));
        data.put("did", compact(record.getDid(), 420));
        data.put("learned", compact(record.getLearned(), 420));
        data.put("completedAt", record.getCompletedAt());
        return data;
    }

    private String textOfHistory(List<AchievementRecordEntity> records) {
        return records.stream()
                .map(record -> String.join(" ",
                        record.getEventTitle(),
                        record.getOrganizationName(),
                        record.getCategory(),
                        nullToEmpty(record.getSkill()),
                        nullToEmpty(record.getDid()),
                        nullToEmpty(record.getLearned())))
                .reduce("", (left, right) -> left + " " + right);
    }

    private String textOfEvent(EventEntity event) {
        return String.join(" ",
                event.getTitle(),
                event.getOrganizationName(),
                event.getCategory().label(),
                event.getLocation(),
                event.getContent(),
                nullToEmpty(event.getSkill()),
                event.getBenefitType().label()
        );
    }

    private void recordArtifact(Long runId,
                                AgentStepName stepName,
                                String artifactType,
                                Object content,
                                String summary) {
        traceArtifactService.record(runId, stepName, artifactType, content, summary);
    }

    private Map<String, Object> queryArtifact(RetrievalDtos.QueryRewrite query,
                                              RetrievalDtos.SearchSessionContext previousContext,
                                              RetrievalDtos.SearchSessionContext savedContext) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", query.mode());
        data.put("originalQuery", query.originalQuery());
        data.put("rewrittenQuery", query.rewrittenQuery());
        data.put("goal", query.goal());
        data.put("level", query.level());
        data.put("intentTags", query.intentTags());
        data.put("skills", query.skills());
        data.put("preferredCategories", query.preferredCategories());
        data.put("preferredLocation", query.preferredLocation());
        data.put("benefitPreference", query.benefitPreference());
        data.put("constraints", query.constraints());
        data.put("contextDecision", query.contextDecision());
        data.put("previousContext", previousContext);
        data.put("savedContext", savedContext);
        data.put("evidence", query.evidence());
        return data;
    }

    private Map<String, Object> retrievalArtifact(List<RetrievedEvent> events) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidateCount", events.size());
        data.put("topCandidates", events.stream()
                .limit(12)
                .map(item -> eventContext(item.event(), item))
                .toList());
        return data;
    }

    private List<Map<String, Object>> recommendationArtifact(List<AiDtos.RecommendedEvent> recommendations) {
        return recommendations.stream()
                .limit(MAX_RECOMMENDATIONS)
                .map(item -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("eventId", item.event().id());
                    data.put("title", item.event().title());
                    data.put("score", item.score());
                    data.put("confidence", item.confidence());
                    data.put("reason", compact(item.reason(), 500));
                    data.put("evidence", item.evidence());
                    return data;
                })
                .toList();
    }

    private Map<String, Object> planGoalArtifact(AiDtos.PlanGoalUnderstanding goal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", goal.goal());
        data.put("level", goal.level());
        data.put("horizonDays", goal.horizonDays());
        data.put("intensity", goal.intensity());
        data.put("preferredLocation", goal.preferredLocation());
        data.put("constraints", goal.constraints());
        data.put("successCriteria", goal.successCriteria());
        data.put("searchQuery", goal.searchQuery());
        return data;
    }

    private List<Map<String, Object>> scheduleArtifact(List<ScheduleDtos.ScheduleItemResponse> schedule) {
        return schedule.stream()
                .limit(12)
                .map(item -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("id", item.id());
                    data.put("title", item.title());
                    data.put("itemType", item.itemType());
                    data.put("startTime", item.startTime());
                    data.put("endTime", item.endTime());
                    data.put("sourceId", item.sourceId());
                    data.put("location", item.location());
                    data.put("status", item.status());
                    return data;
                })
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AI prompt context.", ex);
        }
    }

    private String cleanText(String value, String fallback, int maxLength) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private List<String> cleanList(List<String> values, List<String> fallback, int maxItems, int maxLength) {
        List<String> cleaned = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> cleanText(value, "", maxLength))
                .limit(maxItems)
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String compact(String value, int maxLength) {
        String text = nullToEmpty(value);
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeBenefitPreference(String value) {
        String normalized = nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "skill", "money", "both" -> normalized;
            default -> "";
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private String fallbackMode() {
        return "mock".equalsIgnoreCase(aiMode) ? "mock" : aiMode + ":mock-fallback";
    }

    private boolean hasHardFilter(AiDtos.EventRecommendationRequest request) {
        return isPresent(request.category()) || isPresent(request.benefitType()) || isPresent(request.location());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PlanEvidencePack(
            AiDtos.PlanRecommendationRequest request,
            AiDtos.PlanGoalUnderstanding goal,
            UserMemoryDtos.Profile memory,
            List<RetrievedEvent> retrievedEvents,
            List<ScheduleDtos.ScheduleItemResponse> schedule,
            McpDtos.ToolContextResponse toolContext
    ) {
    }

    private record ResumeEvidencePack(
            List<ResumeTagEvidence> topTags,
            List<ResumeMilestoneEvidence> milestoneEvidence,
            List<ResumeMilestoneEvidence> recentEvidence
    ) {
    }

    private record ResumeTagEvidence(
            String name,
            String description,
            int score,
            int evidenceCount,
            int importanceScore
    ) {
    }

    private record ResumeMilestoneEvidence(
            String title,
            String tagName,
            String summary,
            String did,
            String learned,
            int scoreDelta,
            boolean milestone,
            String milestoneReason,
            String occurredAt
    ) {
    }

    private record RuleMatch(EventEntity event, int score, List<String> evidence) {
    }
}
