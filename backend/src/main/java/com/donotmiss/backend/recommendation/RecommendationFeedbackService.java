package com.donotmiss.backend.recommendation;

import com.donotmiss.backend.ai.AiDtos;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.memory.UserProfileSnapshotDirtyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Closes the recommendation loop: records what was shown (impressions) and how the student reacted
 * (feedback), and answers the one structural question recommendation asks today: which events did
 * this student explicitly mark as not relevant.
 */
@Service
public class RecommendationFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedbackService.class);
    private static final int NEED_TEXT_LIMIT = 500;
    private static final int REASON_LIMIT = 500;

    private final RecommendationImpressionRepository impressionRepository;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final EventRepository eventRepository;
    private final EvalCandidateRepository evalCandidateRepository;
    private final UserProfileSnapshotDirtyService profileDirtyService;
    private final Duration attributionWindow;
    private final Duration profileWindow;

    public RecommendationFeedbackService(RecommendationImpressionRepository impressionRepository,
                                         RecommendationFeedbackRepository feedbackRepository,
                                         EventRepository eventRepository,
                                         EvalCandidateRepository evalCandidateRepository,
                                         UserProfileSnapshotDirtyService profileDirtyService,
                                         @Value("${app.recommendation.feedback.attribution-days:7}") int attributionDays,
                                         @Value("${app.recommendation.feedback.profile-days:90}") int profileDays) {
        this.impressionRepository = impressionRepository;
        this.feedbackRepository = feedbackRepository;
        this.eventRepository = eventRepository;
        this.evalCandidateRepository = evalCandidateRepository;
        this.profileDirtyService = profileDirtyService;
        this.attributionWindow = Duration.ofDays(Math.max(1, attributionDays));
        this.profileWindow = Duration.ofDays(Math.max(1, profileDays));
    }

    // ---- Impressions ----

    /**
     * Own transaction: the caller ({@code AiService.recommendEvents}) runs read-only, and writes
     * joined to a read-only transaction are never flushed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordImpressions(Long runId, String userId, String need, List<AiDtos.RecommendedEvent> recommendations) {
        if (userId == null || recommendations == null || recommendations.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<RecommendationImpressionEntity> rows = new ArrayList<>();
        int rank = 1;
        for (AiDtos.RecommendedEvent item : recommendations) {
            if (item == null || item.event() == null || item.event().id() == null) {
                continue;
            }
            RecommendationImpressionEntity row = new RecommendationImpressionEntity();
            row.setRunId(runId);
            row.setUserId(userId);
            row.setEventId(item.event().id());
            row.setRankNo(rank++);
            row.setScore(item.score());
            row.setNeedText(truncate(need, NEED_TEXT_LIMIT));
            row.setShownAt(now);
            rows.add(row);
        }
        impressionRepository.saveAll(rows);
    }

    // ---- Explicit feedback ----

    /**
     * Sets the student's explicit verdict on one recommended event: INTERESTED, NOT_RELEVANT, or
     * null to clear it. The latest click wins; a student can change their mind.
     */
    @Transactional
    public RecommendationFeedbackDtos.FeedbackState submit(String userId, RecommendationFeedbackDtos.SubmitRequest request) {
        if (request.type() != null && !request.type().explicit()) {
            throw ApiException.badRequest("只能提交“感兴趣”或“不相关”");
        }
        RecommendationImpressionEntity impression = impressionRepository
                .findFirstByRunIdAndUserIdAndEventId(request.runId(), userId, request.eventId())
                .orElseThrow(() -> ApiException.notFound("没有找到这条推荐记录"));
        List<RecommendationFeedbackEntity> previous = feedbackRepository.findByImpressionIdAndExplicitFeedbackTrue(impression.getId());
        feedbackRepository.deleteAll(previous);
        feedbackRepository.flush();
        if (request.type() != null) {
            String reason = request.type() == RecommendationFeedbackType.NOT_RELEVANT ? truncate(request.reason(), REASON_LIMIT) : null;
            feedbackRepository.save(RecommendationFeedbackEntity.of(impression, request.type(), reason));
        }
        syncEvalCandidate(impression, request.type(), truncate(request.reason(), REASON_LIMIT));
        markProfileDirty(userId);
        return new RecommendationFeedbackDtos.FeedbackState(request.eventId(), request.type(),
                request.type() == RecommendationFeedbackType.NOT_RELEVANT ? truncate(request.reason(), REASON_LIMIT) : null);
    }

    /** The explicit verdicts on one run, so the page can show which buttons are already pressed. */
    @Transactional(readOnly = true)
    public List<RecommendationFeedbackDtos.FeedbackState> stateOfRun(String userId, Long runId) {
        List<RecommendationImpressionEntity> impressions = impressionRepository.findByRunIdAndUserId(runId, userId);
        if (impressions.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> eventByImpression = impressions.stream()
                .collect(Collectors.toMap(RecommendationImpressionEntity::getId, RecommendationImpressionEntity::getEventId, (a, b) -> a));
        return feedbackRepository.findByImpressionIdIn(eventByImpression.keySet()).stream()
                .filter(RecommendationFeedbackEntity::isExplicitFeedback)
                .map(item -> new RecommendationFeedbackDtos.FeedbackState(item.getEventId(), item.getFeedbackType(), item.getReason()))
                .toList();
    }

    /** Events this student explicitly marked as not relevant and has not changed their mind about since. */
    @Transactional(readOnly = true)
    public Set<Long> suppressedEventIds(String userId) {
        if (userId == null) {
            return Set.of();
        }
        return suppressed(feedbackRepository.findByUserIdAndExplicitFeedbackTrueOrderByUpdatedAtAsc(userId));
    }

    /** Latest explicit verdict per event decides; input is ordered oldest first. */
    static Set<Long> suppressed(List<RecommendationFeedbackEntity> explicitOldestFirst) {
        Map<Long, RecommendationFeedbackType> latest = new LinkedHashMap<>();
        explicitOldestFirst.forEach(item -> latest.put(item.getEventId(), item.getFeedbackType()));
        return latest.entrySet().stream()
                .filter(entry -> entry.getValue() == RecommendationFeedbackType.NOT_RELEVANT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * A "not relevant" click proposes an evaluation case. If the student takes it back before an
     * operator looked at it, the proposal goes too; once reviewed, the operator's decision stands.
     */
    private void syncEvalCandidate(RecommendationImpressionEntity impression, RecommendationFeedbackType type, String reason) {
        Optional<EvalCandidateEntity> existing = evalCandidateRepository.findByImpressionId(impression.getId());
        if (type == RecommendationFeedbackType.NOT_RELEVANT) {
            if (existing.isEmpty()) {
                evalCandidateRepository.save(EvalCandidateEntity.from(impression, reason));
            } else if (existing.get().getStatus() == EvalCandidateStatus.PENDING) {
                existing.get().updateReason(reason);
            }
            return;
        }
        existing.filter(candidate -> candidate.getStatus() == EvalCandidateStatus.PENDING)
                .ifPresent(evalCandidateRepository::delete);
    }

    // ---- Implicit feedback ----

    /**
     * Attributes a later action to the latest impression inside the window. Runs in its own
     * transaction after the business transaction committed, so it can neither fail nor roll back
     * the reservation or follow that triggered it. Duplicate actions are ignored.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> recordImplicit(RecommendationActivityEvent activity) {
        Instant since = Instant.now().minus(attributionWindow);
        Optional<RecommendationImpressionEntity> impression = activity.eventId() != null
                ? impressionRepository.findFirstByUserIdAndEventIdAndShownAtGreaterThanEqualOrderByShownAtDesc(
                activity.userId(), activity.eventId(), since)
                : impressionRepository.findRecentForOrganization(activity.userId(), activity.organizationName(), since,
                PageRequest.of(0, 1)).stream().findFirst();
        if (impression.isEmpty()) {
            return Optional.empty();
        }
        if (feedbackRepository.existsByImpressionIdAndFeedbackType(impression.get().getId(), activity.type())) {
            return Optional.empty();
        }
        RecommendationFeedbackEntity saved = feedbackRepository.save(
                RecommendationFeedbackEntity.of(impression.get(), activity.type(), null));
        markProfileDirty(activity.userId());
        log.info("Recommendation feedback attributed. userId={}, eventId={}, type={}, impressionId={}",
                activity.userId(), impression.get().getEventId(), activity.type(), impression.get().getId());
        return Optional.ofNullable(saved.getId());
    }

    // ---- Profile input ----

    /**
     * Recent reactions with event context, newest first. The profile agent reads these to learn
     * what kinds of recommendations this student takes up or turns down.
     */
    @Transactional(readOnly = true)
    public List<RecommendationFeedbackDtos.FeedbackSignal> recentSignals(String userId) {
        if (userId == null) {
            return List.of();
        }
        List<RecommendationFeedbackEntity> feedback = feedbackRepository
                .findTop40ByUserIdAndUpdatedAtAfterOrderByUpdatedAtDesc(userId, Instant.now().minus(profileWindow));
        if (feedback.isEmpty()) {
            return List.of();
        }
        Map<Long, EventEntity> events = eventRepository.findAllById(
                        feedback.stream().map(RecommendationFeedbackEntity::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(EventEntity::getId, event -> event, (a, b) -> a));
        return feedback.stream().map(item -> {
            EventEntity event = events.get(item.getEventId());
            return new RecommendationFeedbackDtos.FeedbackSignal(
                    item.getFeedbackType(),
                    item.getFeedbackType().positive(),
                    item.isExplicitFeedback(),
                    item.getEventId(),
                    event == null ? null : event.getTitle(),
                    event == null ? null : event.getCategory().label(),
                    event == null ? null : event.getLocation(),
                    event == null ? null : event.getSkill(),
                    item.getReason(),
                    item.getUpdatedAt());
        }).toList();
    }

    /** New feedback changes what the profile should say; the existing async refresh rebuilds it. */
    private void markProfileDirty(String userId) {
        try {
            profileDirtyService.markDirty(userId, "recommendation-feedback");
        } catch (RuntimeException ex) {
            log.warn("Could not mark profile dirty after recommendation feedback. userId={}, reason={}", userId, ex.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
