package com.donotmiss.backend.ops;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.mq.DomainEventOutboxEntity;
import com.donotmiss.backend.mq.DomainEventOutboxStatus;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceStatus;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionStatus;
import com.donotmiss.backend.resumeinterview.ResumeInterviewScoreSummaryEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Follows one piece of evidence through every layer it touches. Two kinds of journey:
 * <ul>
 *     <li>A completed activity or challenge: MySQL record → Outbox → evaluator agent → assessment job →
 *     Outbox → capability pipeline agent → evidence rows.</li>
 *     <li>A resume interview session: session → parser agent → interviewer agent → answers →
 *     answer evaluator agent → evidence candidates → scoring agent → one Outbox message per
 *     evidence → capability pipeline agent → evidence rows.</li>
 * </ul>
 * The tables share no foreign key chain, so the links are the ids inside message payloads and the
 * {@code recordId=} / {@code sessionId=} / {@code resumeEvidenceId=} prefixes agents write into
 * their run input summary.
 */
@Service
public class OpsJourneyService {
    // The id must end at "," or the end, so a guide session UUID like "sessionId=7cee…" never reads as 7.
    static final Pattern RECORD_ID = Pattern.compile("^recordId=(\\d+)(?:,|$)");
    static final Pattern SESSION_ID = Pattern.compile("^sessionId=(\\d+)(?:,|$)");
    static final Pattern RESUME_EVIDENCE_ID = Pattern.compile("^resumeEvidenceId=(\\d+)(?:,|$)");
    private static final List<AgentRunType> RECORD_RUN_TYPES =
            List.of(AgentRunType.ABILITY_EVIDENCE_ASSESSMENT, AgentRunType.CAPABILITY_V2_PIPELINE);
    private static final List<AgentRunType> RESUME_RUN_TYPES = List.of(
            AgentRunType.RESUME_PARSER,
            AgentRunType.RESUME_INTERVIEWER,
            AgentRunType.RESUME_ANSWER_EVALUATOR,
            AgentRunType.RESUME_ABILITY_SCORING);
    private static final Map<AgentRunType, String> AGENT_NAMES = Map.of(
            AgentRunType.ABILITY_EVIDENCE_ASSESSMENT, "证据评估 Agent",
            AgentRunType.CAPABILITY_V2_PIPELINE, "双标签归因 Agent",
            AgentRunType.RESUME_PARSER, "简历解析 Agent",
            AgentRunType.RESUME_INTERVIEWER, "面试追问 Agent",
            AgentRunType.RESUME_ANSWER_EVALUATOR, "回答评估 Agent",
            AgentRunType.RESUME_ABILITY_SCORING, "初始定段 Agent");
    private static final int RUN_SCAN_LIMIT = 400;

    private final OpsQueries queries;
    private final OpsMessagingService messagingService;

    public OpsJourneyService(OpsQueries queries, OpsMessagingService messagingService) {
        this.queries = queries;
        this.messagingService = messagingService;
    }

    @Transactional(readOnly = true)
    public List<OpsDtos.UserOption> users() {
        return queries.users().stream()
                .map(user -> new OpsDtos.UserOption(user.getPublicUserId(), user.getUsername(), roleName(user)))
                .toList();
    }

    // ---- Activity / challenge completions ----

    @Transactional(readOnly = true)
    public List<OpsDtos.Journey> journeys(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String user = userId.trim();
        List<AchievementRecordEntity> records = queries.records(user, PageRequest.of(0, clampLimit(limit)));
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> recordIds = records.stream().map(AchievementRecordEntity::getId).toList();
        Map<Long, List<AbilityAssessmentJobEntity>> jobs = queries.jobsOf(recordIds).stream()
                .collect(Collectors.groupingBy(AbilityAssessmentJobEntity::getAchievementRecordId));
        Map<Long, List<AgentRunEntity>> runs = runsByPrefix(user, RECORD_RUN_TYPES, "recordId=%", RECORD_ID);
        Map<Long, Long> linkCounts = linkCounts(runs.values().stream().flatMap(List::stream).toList());
        Instant now = Instant.now();
        List<OpsDtos.Journey> journeys = new ArrayList<>();
        for (AchievementRecordEntity record : records) {
            journeys.add(build(record, outboxFor("recordId", record.getId()), jobs.getOrDefault(record.getId(), List.of()),
                    runs.getOrDefault(record.getId(), List.of()), linkCounts, now));
        }
        return journeys;
    }

    OpsDtos.Journey build(AchievementRecordEntity record,
                          List<DomainEventOutboxEntity> messages,
                          List<AbilityAssessmentJobEntity> jobs,
                          List<AgentRunEntity> runs,
                          Map<Long, Long> linkCounts,
                          Instant now) {
        List<OpsDtos.JourneyStep> steps = new ArrayList<>();
        steps.add(new OpsDtos.JourneyStep("SQL", "成果记录写入 achievement_records", "OK",
                record.getCompletedAt(), "记录 #" + record.getId(), record.getId()));
        messages.forEach(message -> steps.add(outboxStep(message, now)));
        for (AbilityAssessmentJobEntity job : jobs) {
            String status = switch (job.getStatus()) {
                case COMPLETED -> "OK";
                case FAILED -> "FAILED";
                case REVIEW_REQUIRED -> "REVIEW";
                default -> "RUNNING";
            };
            String detail = job.getStatus().name() + "，尝试 " + job.getAttemptCount() + " 次"
                    + (job.getErrorMessage() == null ? "" : "；" + job.getErrorMessage());
            steps.add(new OpsDtos.JourneyStep("SQL", "评估任务 ability_assessment_jobs", status,
                    job.getCompletedAt() != null ? job.getCompletedAt() : job.getCreatedAt(), detail, job.getId()));
        }
        Map<AgentRunType, Instant> queuedAt = new HashMap<>();
        for (DomainEventOutboxEntity message : messages) {
            AgentRunType consumer = "capability-v2.score".equals(message.getRoutingKey())
                    ? AgentRunType.CAPABILITY_V2_PIPELINE : AgentRunType.ABILITY_EVIDENCE_ASSESSMENT;
            queuedAt.merge(consumer, message.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Set<Long> superseded = supersededRuns(runs, AgentRunEntity::getRunType, queuedAt::get);
        runs.stream().sorted(Comparator.comparing(AgentRunEntity::getId))
                .forEach(run -> addRunSteps(steps, run, linkCounts, superseded));
        return finish(record.getId(), record.getEventTitle(),
                record.getSourceType() == null ? null : record.getSourceType().name(),
                record.getCompletedAt(), steps);
    }

    // ---- Resume interviews ----

    @Transactional(readOnly = true)
    public List<OpsDtos.Journey> resumeJourneys(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String user = userId.trim();
        List<ResumeInterviewSessionEntity> sessions = queries.resumeSessions(user, PageRequest.of(0, clampLimit(limit)));
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> sessionIds = sessions.stream().map(ResumeInterviewSessionEntity::getId).toList();
        Map<Long, List<ResumeInterviewQuestionEntity>> questions = groupBy(
                queries.resumeQuestionsOf(sessionIds), ResumeInterviewQuestionEntity::getSessionId);
        Map<Long, List<ResumeInterviewEvidenceEntity>> evidence = groupBy(
                queries.resumeEvidenceOf(sessionIds), ResumeInterviewEvidenceEntity::getSessionId);
        Map<Long, ResumeInterviewScoreSummaryEntity> scores = queries.resumeScoresOf(sessionIds).stream()
                .collect(Collectors.toMap(ResumeInterviewScoreSummaryEntity::getSessionId, Function.identity(), (a, b) -> a));
        Map<Long, List<AgentRunEntity>> sessionRuns = runsByPrefix(user, RESUME_RUN_TYPES, "sessionId=%", SESSION_ID);
        Map<Long, List<AgentRunEntity>> evidenceRuns = runsByPrefix(
                user, List.of(AgentRunType.CAPABILITY_V2_PIPELINE), "resumeEvidenceId=%", RESUME_EVIDENCE_ID);
        Map<Long, Long> linkCounts = linkCounts(evidenceRuns.values().stream().flatMap(List::stream).toList());
        Instant now = Instant.now();

        List<OpsDtos.Journey> journeys = new ArrayList<>();
        for (ResumeInterviewSessionEntity session : sessions) {
            List<ResumeInterviewEvidenceEntity> sessionEvidence = evidence.getOrDefault(session.getId(), List.of());
            List<DomainEventOutboxEntity> messages = new ArrayList<>();
            List<AgentRunEntity> pipelineRuns = new ArrayList<>();
            Map<Long, Instant> queuedAt = new HashMap<>();
            for (ResumeInterviewEvidenceEntity item : sessionEvidence) {
                List<DomainEventOutboxEntity> evidenceMessages = outboxFor("resumeEvidenceId", item.getId());
                messages.addAll(evidenceMessages);
                evidenceMessages.stream().map(DomainEventOutboxEntity::getCreatedAt).max(Comparator.naturalOrder())
                        .ifPresent(at -> queuedAt.put(item.getId(), at));
                pipelineRuns.addAll(evidenceRuns.getOrDefault(item.getId(), List.of()));
            }
            journeys.add(buildResume(session,
                    questions.getOrDefault(session.getId(), List.of()),
                    sessionEvidence,
                    scores.get(session.getId()),
                    sessionRuns.getOrDefault(session.getId(), List.of()),
                    messages, pipelineRuns, queuedAt, linkCounts, now));
        }
        return journeys;
    }

    OpsDtos.Journey buildResume(ResumeInterviewSessionEntity session,
                                List<ResumeInterviewQuestionEntity> questions,
                                List<ResumeInterviewEvidenceEntity> evidence,
                                ResumeInterviewScoreSummaryEntity score,
                                List<AgentRunEntity> sessionRuns,
                                List<DomainEventOutboxEntity> messages,
                                List<AgentRunEntity> pipelineRuns,
                                Map<Long, Instant> queuedAt,
                                Map<Long, Long> linkCounts,
                                Instant now) {
        List<OpsDtos.JourneyStep> steps = new ArrayList<>();
        steps.add(new OpsDtos.JourneyStep("SQL", "定段会话 resume_interview_sessions", sessionStatus(session.getStatus()),
                session.getCreatedAt(),
                "目标岗位：" + session.getTargetRole() + "；状态 " + session.getStatus()
                        + (session.getErrorMessage() == null ? "" : "；" + session.getErrorMessage()),
                session.getId()));
        if (!questions.isEmpty()) {
            long answered = questions.stream().filter(question -> question.getStatus() == ResumeInterviewQuestionStatus.ANSWERED
                    || question.getStatus() == ResumeInterviewQuestionStatus.EVALUATED).count();
            long skipped = questions.stream().filter(question -> question.getStatus() == ResumeInterviewQuestionStatus.SKIPPED).count();
            boolean open = answered + skipped < questions.size();
            Instant last = questions.stream().map(question -> question.getAnsweredAt() != null ? question.getAnsweredAt() : question.getCreatedAt())
                    .max(Comparator.naturalOrder()).orElse(null);
            steps.add(new OpsDtos.JourneyStep("SQL", "面试问答 resume_interview_questions",
                    open && isActive(session.getStatus()) ? "WAITING" : "OK", last,
                    "共 " + questions.size() + " 题，已回答 " + answered + (skipped > 0 ? "，跳过 " + skipped : ""), null));
        }
        if (!evidence.isEmpty()) {
            Map<ResumeInterviewEvidenceStatus, Long> byStatus = new EnumMap<>(ResumeInterviewEvidenceStatus.class);
            evidence.forEach(item -> byStatus.merge(item.getStatus(), 1L, Long::sum));
            String status = byStatus.containsKey(ResumeInterviewEvidenceStatus.FAILED) ? "FAILED"
                    : byStatus.containsKey(ResumeInterviewEvidenceStatus.SCORING) ? "RUNNING" : "OK";
            Instant last = evidence.stream().map(ResumeInterviewEvidenceEntity::getUpdatedAt)
                    .filter(value -> value != null).max(Comparator.naturalOrder()).orElse(null);
            steps.add(new OpsDtos.JourneyStep("SQL", "证据候选 resume_interview_evidence_candidates", status, last,
                    evidence.size() + " 条：" + byStatus, null));
        }
        if (score != null) {
            String status = switch (score.getStatus()) {
                case SCORED -> "OK";
                case FAILED -> "FAILED";
                default -> "RUNNING";
            };
            steps.add(new OpsDtos.JourneyStep("SQL", "定段结果 resume_interview_score_summaries", status,
                    score.getCompletedAt() != null ? score.getCompletedAt() : score.getUpdatedAt(),
                    score.getStatus().name() + (score.getErrorMessage() == null ? "" : "；" + score.getErrorMessage()),
                    score.getId()));
        }
        sessionRuns.stream().sorted(Comparator.comparing(AgentRunEntity::getId))
                .forEach(run -> addRunSteps(steps, run, linkCounts, Set.of()));
        messages.forEach(message -> steps.add(outboxStep(message, now)));
        Set<Long> superseded = supersededRuns(pipelineRuns,
                run -> idOf(RESUME_EVIDENCE_ID, run.getInputSummary()).orElse(-1L), queuedAt::get);
        pipelineRuns.stream().sorted(Comparator.comparing(AgentRunEntity::getId))
                .forEach(run -> addRunSteps(steps, run, linkCounts, superseded));
        return finish(session.getId(), "简历定段：" + session.getTargetRole(), "RESUME_INTERVIEW", session.getCreatedAt(), steps);
    }

    private static String sessionStatus(ResumeInterviewSessionStatus status) {
        return switch (status) {
            case COMPLETED, SCORED -> "OK";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case INTERVIEWING -> "WAITING";
            default -> "RUNNING";
        };
    }

    private static boolean isActive(ResumeInterviewSessionStatus status) {
        return status != ResumeInterviewSessionStatus.FAILED && status != ResumeInterviewSessionStatus.CANCELLED;
    }

    // ---- Shared step builders ----

    private OpsDtos.JourneyStep outboxStep(DomainEventOutboxEntity message, Instant now) {
        OpsDtos.OutboxRow row = messagingService.toRow(message, now);
        String status = row.exhausted() ? "FAILED"
                : row.deferred() ? "DEFERRED"
                : message.getStatus() == DomainEventOutboxStatus.SENT ? "OK"
                : message.getStatus() == DomainEventOutboxStatus.FAILED ? "RETRYING" : "PENDING";
        String detail = (message.getAttempts() == 0
                ? (message.getStatus() == DomainEventOutboxStatus.SENT ? "首次即投递成功" : "尚未尝试投递")
                : "投递失败 " + message.getAttempts() + " 次")
                + (message.getLastError() == null ? "" : "；最近错误：" + message.getLastError());
        return new OpsDtos.JourneyStep("MQ", "Outbox → " + message.getRoutingKey(), status,
                message.getSentAt() != null ? message.getSentAt() : message.getCreatedAt(), detail, message.getId());
    }

    /**
     * A failed attempt is history, not the current state, when the same evidence was attempted again
     * later or a new message for it was queued after the attempt started (the model-outage deferral
     * re-queues the message and runs the pipeline again). Only the last attempt decides the verdict.
     */
    static <K> Set<Long> supersededRuns(List<AgentRunEntity> runs,
                                        Function<AgentRunEntity, K> evidenceKey,
                                        Function<K, Instant> lastQueuedAt) {
        Map<K, Long> latest = new HashMap<>();
        runs.forEach(run -> latest.merge(evidenceKey.apply(run), run.getId(), Math::max));
        Set<Long> superseded = new HashSet<>();
        for (AgentRunEntity run : runs) {
            if (run.getStatus() != AgentRunStatus.FAILED) {
                continue;
            }
            K key = evidenceKey.apply(run);
            Instant queued = lastQueuedAt.apply(key);
            boolean laterRun = !run.getId().equals(latest.get(key));
            boolean requeued = queued != null && run.getStartedAt() != null && queued.isAfter(run.getStartedAt());
            if (laterRun || requeued) {
                superseded.add(run.getId());
            }
        }
        return superseded;
    }

    private static void addRunSteps(List<OpsDtos.JourneyStep> steps, AgentRunEntity run, Map<Long, Long> linkCounts,
                                    Set<Long> superseded) {
        boolean retried = superseded.contains(run.getId());
        String status = retried ? "RETRIED" : switch (run.getStatus()) {
            case SUCCEEDED -> "OK";
            case FAILED -> "FAILED";
            case RUNNING -> "RUNNING";
        };
        String detail = run.getStatus() == AgentRunStatus.FAILED
                ? (retried ? "之后已重试：" : "") + run.getErrorMessage()
                : run.getOutputSummary();
        steps.add(new OpsDtos.JourneyStep("AGENT", AGENT_NAMES.getOrDefault(run.getRunType(), run.getRunType().name()),
                status, run.getStartedAt(), detail, run.getId()));
        if (run.getRunType() == AgentRunType.CAPABILITY_V2_PIPELINE && run.getStatus() == AgentRunStatus.SUCCEEDED) {
            long links = linkCounts.getOrDefault(run.getId(), 0L);
            steps.add(new OpsDtos.JourneyStep("SQL", "证据写入 capability_v2_evidence_links", links > 0 ? "OK" : "EMPTY",
                    run.getFinishedAt(), links + " 条证据关联", run.getId()));
        }
    }

    private static OpsDtos.Journey finish(Long id, String title, String sourceType, Instant at, List<OpsDtos.JourneyStep> steps) {
        steps.sort(Comparator.comparing(OpsDtos.JourneyStep::at, Comparator.nullsLast(Comparator.naturalOrder())));
        String[] verdict = verdict(steps);
        return new OpsDtos.Journey(id, title, sourceType, at, verdict[0], verdict[1], steps);
    }

    /** MySQL prints JSON as {"key": value, ...}, so an id is followed by "," or "}". */
    private List<DomainEventOutboxEntity> outboxFor(String key, Long id) {
        return queries.outboxMatching("%\"" + key + "\": " + id + ",%", "%\"" + key + "\": " + id + "}%");
    }

    private Map<Long, List<AgentRunEntity>> runsByPrefix(String userId, Collection<AgentRunType> types, String prefix, Pattern pattern) {
        Map<Long, List<AgentRunEntity>> runs = new HashMap<>();
        for (AgentRunEntity run : queries.runsWithInputPrefix(userId, types, prefix, PageRequest.of(0, RUN_SCAN_LIMIT))) {
            idOf(pattern, run.getInputSummary()).ifPresent(id -> runs.computeIfAbsent(id, key -> new ArrayList<>()).add(run));
        }
        return runs;
    }

    private Map<Long, Long> linkCounts(List<AgentRunEntity> runs) {
        List<Long> pipelineRunIds = runs.stream()
                .filter(run -> run.getRunType() == AgentRunType.CAPABILITY_V2_PIPELINE)
                .map(AgentRunEntity::getId)
                .toList();
        Map<Long, Long> counts = new HashMap<>();
        if (!pipelineRunIds.isEmpty()) {
            for (Object[] row : queries.evidenceLinkCounts(pipelineRunIds)) {
                counts.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        return counts;
    }

    /** One line per journey: failed anywhere beats still moving, which beats done. */
    static String[] verdict(List<OpsDtos.JourneyStep> steps) {
        boolean failed = steps.stream().anyMatch(step -> "FAILED".equals(step.status()));
        if (failed) {
            return new String[]{"链路中有失败环节", "ERROR"};
        }
        if (steps.stream().anyMatch(step -> "WAITING".equals(step.status()))) {
            return new String[]{"等待用户回答", "WARN"};
        }
        boolean moving = steps.stream().anyMatch(step -> List.of("PENDING", "RETRYING", "DEFERRED", "RUNNING").contains(step.status()));
        if (moving) {
            return new String[]{"处理中 / 等待重试", "WARN"};
        }
        if (steps.stream().anyMatch(step -> "CANCELLED".equals(step.status()))) {
            return new String[]{"用户已取消", "MUTED"};
        }
        boolean review = steps.stream().anyMatch(step -> "REVIEW".equals(step.status()));
        boolean pipelineDone = steps.stream().anyMatch(step -> step.name().startsWith("证据写入"));
        if (pipelineDone) {
            boolean written = steps.stream().anyMatch(step -> step.name().startsWith("证据写入") && "OK".equals(step.status()));
            return written
                    ? new String[]{review ? "已入账（评估需复核）" : "已入能力账本", review ? "WARN" : "OK"}
                    : new String[]{"已处理，未产生新证据", "OK"};
        }
        if (steps.size() == 1) {
            return new String[]{"未触发后续处理", "MUTED"};
        }
        return new String[]{review ? "评估需人工复核" : "未进入双标签链路", review ? "WARN" : "MUTED"};
    }

    static Optional<Long> recordIdOf(String inputSummary) {
        return idOf(RECORD_ID, inputSummary);
    }

    static Optional<Long> idOf(Pattern pattern, String inputSummary) {
        if (inputSummary == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(inputSummary);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    private static <T> Map<Long, List<T>> groupBy(List<T> items, Function<T, Long> key) {
        return items.stream().collect(Collectors.groupingBy(key));
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 50));
    }

    private static String roleName(UserEntity user) {
        return user.getRole() == null ? null : user.getRole().name();
    }
}
