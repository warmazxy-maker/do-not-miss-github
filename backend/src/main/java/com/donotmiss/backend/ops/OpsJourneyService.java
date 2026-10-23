package com.donotmiss.backend.ops;

import com.donotmiss.backend.abilityscore.AbilityAssessmentJobEntity;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.mq.DomainEventOutboxEntity;
import com.donotmiss.backend.mq.DomainEventOutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Follows one completed activity or challenge through every layer it touches:
 * MySQL record → Outbox message → RabbitMQ consumer → evaluator agent → assessment job →
 * Outbox message → capability pipeline agent → evidence rows. The tables share no foreign key
 * chain, so the links are the record id inside the message payload and the {@code recordId=}
 * prefix the agents write into their run input summary.
 */
@Service
public class OpsJourneyService {
    private static final Pattern RECORD_ID = Pattern.compile("^recordId=(\\d+)");
    private static final List<AgentRunType> RECORD_RUN_TYPES =
            List.of(AgentRunType.ABILITY_EVIDENCE_ASSESSMENT, AgentRunType.CAPABILITY_V2_PIPELINE);

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

    @Transactional(readOnly = true)
    public List<OpsDtos.Journey> journeys(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<AchievementRecordEntity> records = queries.records(userId.trim(), PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> recordIds = records.stream().map(AchievementRecordEntity::getId).toList();
        Map<Long, List<AbilityAssessmentJobEntity>> jobs = queries.jobsOf(recordIds).stream()
                .collect(Collectors.groupingBy(AbilityAssessmentJobEntity::getAchievementRecordId));
        Map<Long, List<AgentRunEntity>> runs = new HashMap<>();
        for (AgentRunEntity run : queries.recordRuns(userId.trim(), RECORD_RUN_TYPES, PageRequest.of(0, 400))) {
            recordIdOf(run.getInputSummary()).ifPresent(id -> runs.computeIfAbsent(id, key -> new ArrayList<>()).add(run));
        }
        List<Long> pipelineRunIds = runs.values().stream().flatMap(List::stream)
                .filter(run -> run.getRunType() == AgentRunType.CAPABILITY_V2_PIPELINE)
                .map(AgentRunEntity::getId)
                .toList();
        Map<Long, Long> linkCounts = new HashMap<>();
        if (!pipelineRunIds.isEmpty()) {
            for (Object[] row : queries.evidenceLinkCounts(pipelineRunIds)) {
                linkCounts.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        Instant now = Instant.now();
        List<OpsDtos.Journey> journeys = new ArrayList<>();
        for (AchievementRecordEntity record : records) {
            List<DomainEventOutboxEntity> messages = queries.outboxMatching(
                    "%\"recordId\": " + record.getId() + ",%", "%\"recordId\": " + record.getId() + "}%");
            journeys.add(build(record, messages, jobs.getOrDefault(record.getId(), List.of()),
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
        for (DomainEventOutboxEntity message : messages) {
            OpsDtos.OutboxRow row = messagingService.toRow(message, now);
            String status = row.exhausted() ? "FAILED"
                    : row.deferred() ? "DEFERRED"
                    : message.getStatus() == DomainEventOutboxStatus.SENT ? "OK"
                    : message.getStatus() == DomainEventOutboxStatus.FAILED ? "RETRYING" : "PENDING";
            String detail = (message.getAttempts() == 0
                    ? (message.getStatus() == DomainEventOutboxStatus.SENT ? "首次即投递成功" : "尚未尝试投递")
                    : "投递失败 " + message.getAttempts() + " 次")
                    + (message.getLastError() == null ? "" : "；最近错误：" + message.getLastError());
            steps.add(new OpsDtos.JourneyStep("MQ", "Outbox → " + message.getRoutingKey(), status,
                    message.getSentAt() != null ? message.getSentAt() : message.getCreatedAt(), detail, message.getId()));
        }
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
        runs.stream().sorted(Comparator.comparing(AgentRunEntity::getId)).forEach(run -> {
            String status = switch (run.getStatus()) {
                case SUCCEEDED -> "OK";
                case FAILED -> "FAILED";
                case RUNNING -> "RUNNING";
            };
            String name = run.getRunType() == AgentRunType.CAPABILITY_V2_PIPELINE ? "双标签归因 Agent" : "证据评估 Agent";
            String detail = run.getStatus() == AgentRunStatus.FAILED
                    ? run.getErrorMessage() : run.getOutputSummary();
            steps.add(new OpsDtos.JourneyStep("AGENT", name, status, run.getStartedAt(), detail, run.getId()));
            if (run.getRunType() == AgentRunType.CAPABILITY_V2_PIPELINE && run.getStatus() == AgentRunStatus.SUCCEEDED) {
                long links = linkCounts.getOrDefault(run.getId(), 0L);
                steps.add(new OpsDtos.JourneyStep("SQL", "证据写入 capability_v2_evidence_links", links > 0 ? "OK" : "EMPTY",
                        run.getFinishedAt(), links + " 条证据关联", run.getId()));
            }
        });
        steps.sort(Comparator.comparing(OpsDtos.JourneyStep::at, Comparator.nullsLast(Comparator.naturalOrder())));
        String[] verdict = verdict(steps);
        return new OpsDtos.Journey(record.getId(), record.getEventTitle(),
                record.getSourceType() == null ? null : record.getSourceType().name(),
                record.getCompletedAt(), verdict[0], verdict[1], steps);
    }

    /** One line per completion: failed anywhere beats still moving, which beats done. */
    static String[] verdict(List<OpsDtos.JourneyStep> steps) {
        boolean failed = steps.stream().anyMatch(step -> "FAILED".equals(step.status()));
        if (failed) {
            return new String[]{"链路中有失败环节", "ERROR"};
        }
        boolean moving = steps.stream().anyMatch(step -> List.of("PENDING", "RETRYING", "DEFERRED", "RUNNING").contains(step.status()));
        if (moving) {
            return new String[]{"处理中 / 等待重试", "WARN"};
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
        if (inputSummary == null) {
            return Optional.empty();
        }
        Matcher matcher = RECORD_ID.matcher(inputSummary);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    private static String roleName(UserEntity user) {
        return user.getRole() == null ? null : user.getRole().name();
    }
}
