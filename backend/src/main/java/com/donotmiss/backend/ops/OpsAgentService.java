package com.donotmiss.backend.ops;

import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.agentlog.AgentRunStepEntity;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.agentlog.AgentStepStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Cross-user agent run list and latency / failure statistics. */
@Service
public class OpsAgentService {
    private static final int TOP_ERRORS = 8;

    private final OpsQueries queries;

    public OpsAgentService(OpsQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public List<OpsDtos.AgentRunRow> runs(String runType, String status, String userId, int limit) {
        return queries.runs(
                        parse(AgentRunType.class, runType),
                        parse(AgentRunStatus.class, status),
                        userId == null || userId.isBlank() ? null : userId.trim(),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(OpsAgentService::toRow)
                .toList();
    }

    /**
     * Per run type: volume, failures, average and p95 duration over the window. Per step of one run
     * type (the capability pipeline by default): where the time goes and which step fails.
     */
    @Transactional(readOnly = true)
    public OpsDtos.AgentRunStats stats(int hours, String stepRunType) {
        int window = Math.max(1, Math.min(hours, 24 * 30));
        List<AgentRunEntity> runs = queries.runsSince(Instant.now().minus(Duration.ofHours(window)));

        Map<AgentRunType, List<AgentRunEntity>> byType = runs.stream()
                .collect(Collectors.groupingBy(AgentRunEntity::getRunType, TreeMap::new, Collectors.toList()));
        List<OpsDtos.AgentRunTypeStats> typeStats = new ArrayList<>();
        byType.forEach((type, list) -> {
            List<Long> finished = list.stream()
                    .filter(run -> run.getFinishedAt() != null)
                    .map(AgentRunEntity::durationMillis)
                    .toList();
            typeStats.add(new OpsDtos.AgentRunTypeStats(
                    type.name(),
                    list.size(),
                    list.stream().filter(run -> run.getStatus() == AgentRunStatus.FAILED).count(),
                    list.stream().filter(run -> run.getStatus() == AgentRunStatus.RUNNING).count(),
                    average(finished),
                    percentile(finished, 0.95)));
        });
        typeStats.sort(Comparator.comparingLong(OpsDtos.AgentRunTypeStats::total).reversed());

        AgentRunType stepType = parse(AgentRunType.class, stepRunType);
        if (stepType == null) {
            stepType = AgentRunType.CAPABILITY_V2_PIPELINE;
        }
        AgentRunType selected = stepType;
        List<Long> runIds = runs.stream().filter(run -> run.getRunType() == selected).map(AgentRunEntity::getId).toList();
        List<OpsDtos.StepStats> steps = runIds.isEmpty() ? List.of() : stepStats(queries.stepsOf(runIds));

        Map<String, Long> errors = runs.stream()
                .filter(run -> run.getStatus() == AgentRunStatus.FAILED && run.getErrorMessage() != null)
                .collect(Collectors.groupingBy(run -> run.getRunType().name() + " · " + firstLine(run.getErrorMessage()),
                        Collectors.counting()));
        List<Map<String, Object>> topErrors = errors.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_ERRORS)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("error", entry.getKey());
                    row.put("count", entry.getValue());
                    return row;
                })
                .toList();
        return new OpsDtos.AgentRunStats(window, typeStats, selected.name(), steps, topErrors);
    }

    static List<OpsDtos.StepStats> stepStats(List<AgentRunStepEntity> steps) {
        Map<String, List<AgentRunStepEntity>> byName = new LinkedHashMap<>();
        steps.stream()
                .sorted(Comparator.comparingInt(AgentRunStepEntity::getSequenceNo))
                .forEach(step -> byName.computeIfAbsent(step.getStepName().name(), key -> new ArrayList<>()).add(step));
        List<OpsDtos.StepStats> result = new ArrayList<>();
        byName.forEach((name, list) -> {
            List<Long> finished = list.stream()
                    .filter(step -> step.getFinishedAt() != null)
                    .map(AgentRunStepEntity::durationMillis)
                    .toList();
            result.add(new OpsDtos.StepStats(
                    name,
                    list.size(),
                    list.stream().filter(step -> step.getStatus() == AgentStepStatus.FAILED).count(),
                    average(finished),
                    percentile(finished, 0.95)));
        });
        return result;
    }

    static OpsDtos.AgentRunRow toRow(AgentRunEntity run) {
        return new OpsDtos.AgentRunRow(
                run.getId(),
                run.getUserId(),
                run.getRunType().name(),
                run.getStatus().name(),
                run.getGoal(),
                run.getInputSummary(),
                run.getOutputSummary(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getFinishedAt() == null ? null : run.durationMillis());
    }

    static Long average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    /** Nearest-rank percentile: the smallest value at or above the given share of the samples. */
    static Long percentile(List<Long> values, double share) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(share * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static String firstLine(String message) {
        String line = message.lines().findFirst().orElse(message).trim();
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        return Enum.valueOf(type, value.trim().toUpperCase());
    }
}
