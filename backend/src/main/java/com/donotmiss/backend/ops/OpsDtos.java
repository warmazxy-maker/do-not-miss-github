package com.donotmiss.backend.ops;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read models of the operations console. */
public final class OpsDtos {
    private OpsDtos() {
    }

    public enum HealthState { UP, DEGRADED, DOWN, DISABLED }

    /** One external dependency: MySQL, Redis, RabbitMQ, OpenSearch, chat model, embeddings. */
    public record DependencyHealth(
            String key,
            String name,
            HealthState state,
            Long latencyMillis,
            String summary,
            Map<String, Object> details
    ) {
    }

    public record Overview(
            Instant checkedAt,
            List<DependencyHealth> dependencies,
            Map<String, Long> counters
    ) {
    }

    // ---- Agent runs ----

    public record AgentRunRow(
            Long id,
            String userId,
            String runType,
            String status,
            String goal,
            String inputSummary,
            String outputSummary,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Long durationMillis
    ) {
    }

    public record AgentRunTypeStats(
            String runType,
            long total,
            long failed,
            long running,
            Long avgMillis,
            Long p95Millis
    ) {
    }

    public record StepStats(
            String stepName,
            long total,
            long failed,
            Long avgMillis,
            Long p95Millis
    ) {
    }

    public record AgentRunStats(
            int windowHours,
            List<AgentRunTypeStats> byType,
            String stepRunType,
            List<StepStats> steps,
            List<Map<String, Object>> topErrors
    ) {
    }

    // ---- Messaging ----

    public record QueueRow(
            String queue,
            String routingKey,
            Long messageCount,
            Integer consumerCount,
            Boolean listenerRunning,
            String error
    ) {
    }

    public record OutboxRow(
            Long id,
            String routingKey,
            String payloadType,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant sentAt,
            String payloadJson,
            boolean deferred,
            boolean exhausted
    ) {
    }

    public record OutboxStats(
            int maxAttempts,
            List<Map<String, Object>> byRoutingKey,
            long pending,
            long deferred,
            long failed,
            long exhausted
    ) {
    }

    public record Messaging(
            String exchange,
            List<QueueRow> queues,
            OutboxStats outbox
    ) {
    }

    // ---- Storage ----

    public record RedisPrefix(String prefix, String meaning, long count, boolean capped) {
    }

    public record RedisInfo(
            HealthState state,
            Long latencyMillis,
            Long dbSize,
            String usedMemory,
            String maxMemory,
            List<RedisPrefix> prefixes,
            String error
    ) {
    }

    public record Migration(String version, String description, boolean success, Instant installedOn, Long executionMillis) {
    }

    public record TableSize(String table, Long approxRows, Long dataKb, Long indexKb) {
    }

    public record DatabaseInfo(
            String product,
            String latestVersion,
            List<Migration> migrations,
            Map<String, Object> pool,
            List<TableSize> tables
    ) {
    }

    // ---- Journeys: one completion across SQL → MQ → Agent ----

    public record JourneyStep(
            String layer,
            String name,
            String status,
            Instant at,
            String detail,
            Long refId
    ) {
    }

    public record Journey(
            Long recordId,
            String title,
            String sourceType,
            Instant completedAt,
            String verdict,
            String verdictLevel,
            List<JourneyStep> steps
    ) {
    }

    public record UserOption(String userId, String username, String role) {
    }

    // ---- Recommendations: is what we recommend any good? ----

    /** Counts per impression; rates use impressions as the denominator. */
    public record RecommendationFunnel(
            long impressions,
            long interested,
            long notRelevant,
            long reserved,
            long cancelled,
            long completed,
            long followed,
            long withExplicitFeedback,
            double interestedRate,
            double notRelevantRate,
            double reserveRate,
            double completeRate,
            double followRate,
            double explicitFeedbackRate
    ) {
    }

    public record RecommendationDay(
            String date,
            long runs,
            long zeroResultRuns,
            long impressions,
            long interested,
            long notRelevant,
            long reserved
    ) {
    }

    public record NotRelevantReason(
            Long eventId,
            String eventTitle,
            String userId,
            String reason,
            Instant at,
            Long runId
    ) {
    }

    public record ShownEvent(
            Long eventId,
            String title,
            int rank,
            int score,
            List<String> feedback
    ) {
    }

    public record RecommendationRunRow(
            Long runId,
            String userId,
            String need,
            String status,
            Instant startedAt,
            Long durationMillis,
            Integer recommendationCount,
            List<ShownEvent> shown
    ) {
    }

    public record RecommendationStats(
            int windowDays,
            long runs,
            long failedRuns,
            long zeroResultRuns,
            double zeroResultRate,
            Long avgMillis,
            long studentsReached,
            long suppressedByFeedback,
            Instant trackingSince,
            RecommendationFunnel funnel,
            List<RecommendationDay> daily,
            List<NotRelevantReason> notRelevantReasons
    ) {
    }

    // ---- Evaluation candidates from feedback (B4) ----

    public record EvalCandidateRow(
            Long id,
            Long runId,
            String userId,
            Long eventId,
            String eventTitle,
            String needText,
            String reason,
            String status,
            String reviewerNote,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            boolean inCurrentEvalSet
    ) {
    }

    /** {@code needText} replaces the recorded need on approval, e.g. with personal details removed. */
    public record EvalCandidateReview(String decision, String needText, String note) {
    }

    /** The next evaluation set version, rendered as files for a developer to review and commit. */
    public record EvalExport(
            String evalSetVersion,
            String corpusVersion,
            List<String> newCaseIds,
            List<Long> addedEventIds,
            List<String> warnings,
            String evalSetFileName,
            String evalSetJson,
            String corpusFileName,
            String corpusJson
    ) {
    }

    // ---- Audit log (D1) ----

    public record AuditLogRow(
            Long id,
            String userId,
            String roleCode,
            String permission,
            String action,
            String httpMethod,
            String path,
            String outcome,
            String detail,
            Instant createdAt
    ) {
    }
}
