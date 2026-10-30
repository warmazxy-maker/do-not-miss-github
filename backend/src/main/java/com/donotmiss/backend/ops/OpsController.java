package com.donotmiss.backend.ops;

import com.donotmiss.backend.access.AuditLogService;
import com.donotmiss.backend.access.OperatorAccountService;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
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
    private final AuditLogService auditLogService;
    private final OperatorAccountService operatorAccountService;

    public OpsController(OpsAccessGuard guard,
                         CurrentUser currentUser,
                         OpsHealthService healthService,
                         OpsAgentService agentService,
                         OpsMessagingService messagingService,
                         OpsJourneyService journeyService,
                         AgentRunService agentRunService,
                         OpsRecommendationService recommendationService,
                         OpsEvalCandidateService evalCandidateService,
                         AuditLogService auditLogService,
                         OperatorAccountService operatorAccountService) {
        this.guard = guard;
        this.currentUser = currentUser;
        this.healthService = healthService;
        this.agentService = agentService;
        this.messagingService = messagingService;
        this.journeyService = journeyService;
        this.agentRunService = agentRunService;
        this.recommendationService = recommendationService;
        this.evalCandidateService = evalCandidateService;
        this.auditLogService = auditLogService;
        this.operatorAccountService = operatorAccountService;
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
    @RequirePermission(value = Permission.OPS_ACTION, action = "ops.probeModels")
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
    @RequirePermission(value = Permission.OPS_ACTION, action = "ops.retryOutbox")
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

    /** Operators review candidates without console access, so only the master switch applies here. */
    @GetMapping("/eval-candidates")
    @RequirePermission(Permission.EVAL_CANDIDATE_REVIEW)
    public List<OpsDtos.EvalCandidateRow> evalCandidates(@RequestParam(defaultValue = "PENDING") String status) {
        guard.requireEnabled();
        return evalCandidateService.list(status);
    }

    @PostMapping("/eval-candidates/{id}/review")
    @RequirePermission(value = Permission.EVAL_CANDIDATE_REVIEW, action = "eval.reviewCandidate")
    public void reviewEvalCandidate(@PathVariable Long id,
                                    @RequestBody OpsDtos.EvalCandidateReview review,
                                    HttpServletRequest request) {
        guard.requireEnabled();
        evalCandidateService.review(id, EvalCandidateStatus.valueOf(review.decision().trim().toUpperCase()),
                review.needText(), review.note(), currentUser.id(request));
    }

    @GetMapping("/eval-candidates/export")
    @RequirePermission(Permission.EVAL_CANDIDATE_REVIEW)
    public OpsDtos.EvalExport exportEvalSet() {
        guard.requireEnabled();
        return evalCandidateService.export();
    }

    @GetMapping("/audit-logs")
    @RequirePermission(Permission.AUDIT_VIEW)
    public List<OpsDtos.AuditLogRow> auditLogs(@RequestParam(required = false) String outcome,
                                               @RequestParam(required = false) String userId,
                                               @RequestParam(defaultValue = "100") int limit,
                                               HttpServletRequest request) {
        guard.require(request);
        return auditLogService.search(outcome, userId, limit).stream()
                .map(entry -> new OpsDtos.AuditLogRow(entry.getId(), entry.getUserId(), entry.getRoleCode(),
                        entry.getPermission(), entry.getAction(), entry.getHttpMethod(), entry.getPath(),
                        entry.getOutcome(), entry.getDetail(), entry.getCreatedAt()))
                .toList();
    }

    /** D3: only admin_zero (ROLE_ASSIGN) sees and creates operator accounts. */
    @GetMapping("/operators")
    @RequirePermission(Permission.ROLE_ASSIGN)
    public List<OperatorAccountService.OperatorAccount> operators(HttpServletRequest request) {
        guard.require(request);
        return operatorAccountService.list();
    }

    @PostMapping("/operators")
    @RequirePermission(value = Permission.ROLE_ASSIGN, action = "role.createOperator")
    public OperatorAccountService.CreatedOperator createOperator(@RequestBody OpsDtos.CreateOperator body,
                                                                 HttpServletRequest request) {
        guard.require(request);
        return operatorAccountService.create(body.username());
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
