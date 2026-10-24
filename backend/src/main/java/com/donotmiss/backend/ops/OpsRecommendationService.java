package com.donotmiss.backend.ops;

import com.donotmiss.backend.agentlog.AgentRunEntity;
import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.agentlog.AgentRunType;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.recommendation.RecommendationFeedbackEntity;
import com.donotmiss.backend.recommendation.RecommendationFeedbackType;
import com.donotmiss.backend.recommendation.RecommendationImpressionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Online recommendation metrics for the operations console.
 *
 * <p>Definitions (all over the selected window):</p>
 * <ul>
 *     <li>run: one EVENT_RECOMMENDATION agent run; zero-result run: it recommended nothing
 *     (read from the run summary, so it also covers runs from before impressions were recorded).</li>
 *     <li>impression: one event shown in one run. Every rate below divides by impressions: an
 *     impression with a RESERVED feedback counts once, however many times it was reserved.</li>
 * </ul>
 */
@Service
public class OpsRecommendationService {
    private static final Pattern RECOMMENDATION_COUNT = Pattern.compile("recommendationCount=(\\d+)");
    private static final String SUPPRESSED_ARTIFACT = "SUPPRESSED_BY_FEEDBACK";
    private static final int REASON_LIMIT = 20;

    private final OpsQueries queries;
    private final ZoneId zone;

    public OpsRecommendationService(OpsQueries queries,
                                    @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.queries = queries;
        this.zone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public OpsDtos.RecommendationStats stats(int days) {
        int window = Math.max(1, Math.min(days, 90));
        Instant since = Instant.now().minus(Duration.ofDays(window));
        List<AgentRunEntity> runs = queries.runsOfTypeSince(AgentRunType.EVENT_RECOMMENDATION, since);
        List<RecommendationImpressionEntity> impressions = queries.impressionsSince(since);
        List<RecommendationFeedbackEntity> feedback = impressions.isEmpty() ? List.of()
                : queries.feedbackOf(ids(impressions, RecommendationImpressionEntity::getId));

        long failed = runs.stream().filter(run -> run.getStatus() == AgentRunStatus.FAILED).count();
        List<AgentRunEntity> succeeded = runs.stream().filter(run -> run.getStatus() == AgentRunStatus.SUCCEEDED).toList();
        long zeroResult = succeeded.stream().filter(run -> recommendationCount(run.getOutputSummary()).orElse(-1) == 0).count();
        Long avgMillis = succeeded.isEmpty() ? null
                : Math.round(succeeded.stream().mapToLong(AgentRunEntity::durationMillis).average().orElse(0));
        long suppressed = runs.isEmpty() ? 0 : queries.countArtifacts(ids(runs, AgentRunEntity::getId), SUPPRESSED_ARTIFACT);
        Instant trackingSince = impressions.stream().map(RecommendationImpressionEntity::getShownAt)
                .min(Comparator.naturalOrder()).orElse(null);

        return new OpsDtos.RecommendationStats(
                window,
                runs.size(),
                failed,
                zeroResult,
                rate(zeroResult, succeeded.size()),
                avgMillis,
                impressions.stream().map(RecommendationImpressionEntity::getUserId).distinct().count(),
                suppressed,
                trackingSince,
                funnel(impressions, feedback),
                daily(runs, impressions, feedback, zone),
                notRelevantReasons(impressions, feedback));
    }

    @Transactional(readOnly = true)
    public List<OpsDtos.RecommendationRunRow> recentRuns(int limit) {
        List<AgentRunEntity> runs = queries.runs(AgentRunType.EVENT_RECOMMENDATION, null, null,
                PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
        if (runs.isEmpty()) {
            return List.of();
        }
        List<RecommendationImpressionEntity> impressions = queries.impressionsOfRuns(ids(runs, AgentRunEntity::getId));
        Map<Long, List<String>> feedbackByImpression = new LinkedHashMap<>();
        if (!impressions.isEmpty()) {
            for (RecommendationFeedbackEntity item : queries.feedbackOf(ids(impressions, RecommendationImpressionEntity::getId))) {
                feedbackByImpression.computeIfAbsent(item.getImpressionId(), key -> new ArrayList<>()).add(item.getFeedbackType().name());
            }
        }
        Map<Long, String> titles = titles(impressions.stream().map(RecommendationImpressionEntity::getEventId).toList());
        Map<Long, List<RecommendationImpressionEntity>> byRun = impressions.stream()
                .collect(Collectors.groupingBy(RecommendationImpressionEntity::getRunId));

        return runs.stream().map(run -> new OpsDtos.RecommendationRunRow(
                run.getId(),
                run.getUserId(),
                run.getGoal(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getFinishedAt() == null ? null : run.durationMillis(),
                recommendationCount(run.getOutputSummary()).orElse(null),
                byRun.getOrDefault(run.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(RecommendationImpressionEntity::getRankNo))
                        .map(item -> new OpsDtos.ShownEvent(item.getEventId(), titles.get(item.getEventId()),
                                item.getRankNo(), item.getScore(), feedbackByImpression.getOrDefault(item.getId(), List.of())))
                        .toList()
        )).toList();
    }

    // ---- Pure aggregation ----

    static OpsDtos.RecommendationFunnel funnel(List<RecommendationImpressionEntity> impressions,
                                               List<RecommendationFeedbackEntity> feedback) {
        Map<RecommendationFeedbackType, Set<Long>> byType = new LinkedHashMap<>();
        Set<Long> explicit = new HashSet<>();
        Set<Long> known = impressions.stream().map(RecommendationImpressionEntity::getId).collect(Collectors.toSet());
        for (RecommendationFeedbackEntity item : feedback) {
            if (!known.contains(item.getImpressionId())) {
                continue;
            }
            byType.computeIfAbsent(item.getFeedbackType(), key -> new HashSet<>()).add(item.getImpressionId());
            if (item.isExplicitFeedback()) {
                explicit.add(item.getImpressionId());
            }
        }
        long total = impressions.size();
        long interested = count(byType, RecommendationFeedbackType.INTERESTED);
        long notRelevant = count(byType, RecommendationFeedbackType.NOT_RELEVANT);
        long reserved = count(byType, RecommendationFeedbackType.RESERVED);
        long completed = count(byType, RecommendationFeedbackType.COMPLETED);
        long followed = count(byType, RecommendationFeedbackType.FOLLOWED);
        return new OpsDtos.RecommendationFunnel(
                total, interested, notRelevant, reserved,
                count(byType, RecommendationFeedbackType.RESERVATION_CANCELLED), completed, followed, explicit.size(),
                rate(interested, total), rate(notRelevant, total), rate(reserved, total),
                rate(completed, total), rate(followed, total), rate(explicit.size(), total));
    }

    static List<OpsDtos.RecommendationDay> daily(List<AgentRunEntity> runs,
                                                 List<RecommendationImpressionEntity> impressions,
                                                 List<RecommendationFeedbackEntity> feedback,
                                                 ZoneId zone) {
        Map<LocalDate, long[]> days = new TreeMap<>();
        for (AgentRunEntity run : runs) {
            long[] row = days.computeIfAbsent(LocalDate.ofInstant(run.getStartedAt(), zone), key -> new long[6]);
            row[0]++;
            if (run.getStatus() == AgentRunStatus.SUCCEEDED && recommendationCount(run.getOutputSummary()).orElse(-1) == 0) {
                row[1]++;
            }
        }
        Map<Long, LocalDate> dayOfImpression = new LinkedHashMap<>();
        for (RecommendationImpressionEntity impression : impressions) {
            LocalDate day = LocalDate.ofInstant(impression.getShownAt(), zone);
            dayOfImpression.put(impression.getId(), day);
            days.computeIfAbsent(day, key -> new long[6])[2]++;
        }
        // Attributed to the day the recommendation was shown, so a day's rates describe that day's recommendations.
        Set<String> counted = new HashSet<>();
        for (RecommendationFeedbackEntity item : feedback) {
            LocalDate day = dayOfImpression.get(item.getImpressionId());
            if (day == null || !counted.add(item.getImpressionId() + ":" + item.getFeedbackType())) {
                continue;
            }
            long[] row = days.get(day);
            switch (item.getFeedbackType()) {
                case INTERESTED -> row[3]++;
                case NOT_RELEVANT -> row[4]++;
                case RESERVED -> row[5]++;
                default -> {
                }
            }
        }
        List<OpsDtos.RecommendationDay> result = new ArrayList<>();
        days.forEach((day, row) -> result.add(new OpsDtos.RecommendationDay(day.toString(), row[0], row[1], row[2], row[3], row[4], row[5])));
        return result;
    }

    private List<OpsDtos.NotRelevantReason> notRelevantReasons(List<RecommendationImpressionEntity> impressions,
                                                              List<RecommendationFeedbackEntity> feedback) {
        Map<Long, RecommendationImpressionEntity> byId = impressions.stream()
                .collect(Collectors.toMap(RecommendationImpressionEntity::getId, Function.identity()));
        List<RecommendationFeedbackEntity> rejected = feedback.stream()
                .filter(item -> item.getFeedbackType() == RecommendationFeedbackType.NOT_RELEVANT)
                .sorted(Comparator.comparing(RecommendationFeedbackEntity::getUpdatedAt).reversed())
                .limit(REASON_LIMIT)
                .toList();
        Map<Long, String> titles = titles(rejected.stream().map(RecommendationFeedbackEntity::getEventId).toList());
        return rejected.stream().map(item -> new OpsDtos.NotRelevantReason(
                item.getEventId(),
                titles.get(item.getEventId()),
                item.getUserId(),
                item.getReason(),
                item.getUpdatedAt(),
                Optional.ofNullable(byId.get(item.getImpressionId())).map(RecommendationImpressionEntity::getRunId).orElse(null)
        )).toList();
    }

    private Map<Long, String> titles(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return queries.eventsByIds(new HashSet<>(eventIds)).stream()
                .collect(Collectors.toMap(EventEntity::getId, EventEntity::getTitle, (a, b) -> a));
    }

    static Optional<Integer> recommendationCount(String outputSummary) {
        if (outputSummary == null) {
            return Optional.empty();
        }
        Matcher matcher = RECOMMENDATION_COUNT.matcher(outputSummary);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static long count(Map<RecommendationFeedbackType, Set<Long>> byType, RecommendationFeedbackType type) {
        return byType.getOrDefault(type, Set.of()).size();
    }

    private static <T> List<Long> ids(List<T> items, Function<T, Long> id) {
        return items.stream().map(id).toList();
    }

    private static double rate(long part, long whole) {
        return whole == 0 ? 0 : Math.round((double) part / whole * 1000.0) / 1000.0;
    }
}
