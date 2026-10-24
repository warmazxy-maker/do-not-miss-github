package com.donotmiss.backend.ops;

import com.donotmiss.backend.agentlog.AgentLogDtos;
import com.donotmiss.backend.agentlog.AgentRunService;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.recommendation.EvalCandidateStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operations console: dependency health, agent runs of every user, the Outbox and RabbitMQ queues,
 * Redis, MySQL, and the end-to-end journey of a completion. Every endpoint except {@code /access}
 * checks {@link OpsAccessGuard} first.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {
    private final OpsAccessGuard guard;
    private final CurrentUser currentUser;
    private final OpsHealthService healthService;
    private final OpsAgentService agentService;
    private final OpsMessagingService messagingService;
    private final OpsJourneyService journeyService;
    private final AgentRunService agentRunService;
    private final OpsRecommendationService recommendationService;
    private final OpsEvalCandidateService evalCandidateService;

    public OpsController(OpsAccessGuard guard,
                         CurrentUser currentUser,
                         OpsHealthService healthService,
                         OpsAgentService agentService,
                         OpsMessagingService messagingService,
                         OpsJourneyService journeyService,
                         AgentRunService agentRunService,
                         OpsRecommendationService recommendationService,
                         OpsEvalCandidateService evalCandidateService) {
        this.guard = guard;
        this.currentUser = currentUser;
        this.healthService = healthService;
        this.agentService = agentService;
        this.messagingService = messagingService;
        this.journeyService = journeyService;
        this.agentRunService = agentRunService;
        this.recommendationService = recommendationService;
        this.evalCandidateService = evalCandidateService;
    }

    /** Lets the frontend decide whether to show the entry; never fails. */
    @GetMapping("/access")
    public Map<String, Boolean> access(HttpServletRequest request) {
        return Map.of("allowed", guard.allowed(request));
    }

    @GetMapping("/overview")
    public OpsDtos.Overview overview(HttpServletRequest request) {
        guard.require(request);
        return healthService.overview();
    }

    @PostMapping("/probe/models")
    public List<OpsDtos.DependencyHealth> probeModels(HttpServletRequest request) {
        guard.require(request);
        return healthService.probeModels();
    }

    @GetMapping("/agent-runs")
    public List<OpsDtos.AgentRunRow> agentRuns(@RequestParam(required = false) String runType,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String userId,
                                               @RequestParam(defaultValue = "50") int limit,
                                               HttpServletRequest request) {
        guard.require(request);
        return agentService.runs(runType, status, userId, limit);
    }

    @GetMapping("/agent-runs/stats")
    public OpsDtos.AgentRunStats agentRunStats(@RequestParam(defaultValue = "24") int hours,
                                               @RequestParam(required = false) String stepRunType,
                                               HttpServletRequest request) {
        guard.require(request);
        return agentService.stats(hours, stepRunType);
    }

    @GetMapping("/agent-runs/{runId}")
    public AgentLogDtos.RunDetail agentRun(@PathVariable Long runId, HttpServletRequest request) {
        guard.require(request);
        return agentRunService.detailForOps(runId);
    }

    @GetMapping("/messaging")
    public OpsDtos.Messaging messaging(HttpServletRequest request) {
        guard.require(request);
        return messagingService.messaging();
    }

    @GetMapping("/outbox")
    public List<OpsDtos.OutboxRow> outbox(@RequestParam(defaultValue = "ALL") String view,
                                          @RequestParam(required = false) String routingKey,
                                          @RequestParam(defaultValue = "50") int limit,
                                          HttpServletRequest request) {
        guard.require(request);
        return messagingService.outbox(view, routingKey, limit);
    }

    @PostMapping("/outbox/{id}/retry")
    public OpsDtos.OutboxRow retryOutbox(@PathVariable Long id, HttpServletRequest request) {
        guard.require(request);
        return messagingService.retry(id, currentUser.id(request));
    }

    @GetMapping("/redis")
    public OpsDtos.RedisInfo redis(HttpServletRequest request) {
        guard.require(request);
        return healthService.redisInfo();
    }

    @GetMapping("/database")
    public OpsDtos.DatabaseInfo database(HttpServletRequest request) {
        guard.require(request);
        return healthService.databaseInfo();
    }

    @GetMapping("/recommendations/stats")
    public OpsDtos.RecommendationStats recommendationStats(@RequestParam(defaultValue = "7") int days,
                                                           HttpServletRequest request) {
        guard.require(request);
        return recommendationService.stats(days);
    }

    @GetMapping("/recommendations/runs")
    public List<OpsDtos.RecommendationRunRow> recommendationRuns(@RequestParam(defaultValue = "20") int limit,
                                                                 HttpServletRequest request) {
        guard.require(request);
        return recommendationService.recentRuns(limit);
    }

    @GetMapping("/eval-candidates")
    public List<OpsDtos.EvalCandidateRow> evalCandidates(@RequestParam(defaultValue = "PENDING") String status,
                                                         HttpServletRequest request) {
        guard.require(request);
        return evalCandidateService.list(status);
    }

    @PostMapping("/eval-candidates/{id}/review")
    public void reviewEvalCandidate(@PathVariable Long id,
                                    @RequestBody OpsDtos.EvalCandidateReview review,
                                    HttpServletRequest request) {
        guard.require(request);
        evalCandidateService.review(id, EvalCandidateStatus.valueOf(review.decision().trim().toUpperCase()),
                review.needText(), review.note(), currentUser.id(request));
    }

    @GetMapping("/eval-candidates/export")
    public OpsDtos.EvalExport exportEvalSet(HttpServletRequest request) {
        guard.require(request);
        return evalCandidateService.export();
    }

    @GetMapping("/users")
    public List<OpsDtos.UserOption> users(HttpServletRequest request) {
        guard.require(request);
        return journeyService.users();
    }

    @GetMapping("/journeys")
    public List<OpsDtos.Journey> journeys(@RequestParam String userId,
                                          @RequestParam(defaultValue = "achievement") String kind,
                                          @RequestParam(defaultValue = "15") int limit,
                                          HttpServletRequest request) {
        guard.require(request);
        return "resume".equalsIgnoreCase(kind)
                ? journeyService.resumeJourneys(userId, limit)
                : journeyService.journeys(userId, limit);
    }
}
