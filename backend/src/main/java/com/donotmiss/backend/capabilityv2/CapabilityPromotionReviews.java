package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.JudgeDecision;
import com.donotmiss.backend.abilityscore.JudgeDtos;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Shared view and retry rule for promotion reviews, used by the detail read model and the review service. */
public final class CapabilityPromotionReviews {
    /** After an unsuccessful, model-evaluated review the user waits this long or brings new evidence. */
    public static final Duration RETRY_COOLDOWN = Duration.ofDays(7);

    private CapabilityPromotionReviews() {
    }

    /**
     * Returns when a new review may start, or {@code null} when nothing blocks it.
     * A pass or a review the model could not evaluate never blocks; a fail or an undecided
     * score waits for new evidence on the bucket or the cooldown, so retries cannot be brute forced.
     */
    public static Instant retryAvailableAt(CapabilityPromotionReviewEntity lastCompleted, Instant latestEvidenceAt) {
        if (lastCompleted == null
                || lastCompleted.getCompletedAt() == null
                || lastCompleted.getDecision() == JudgeDecision.PASS
                || !lastCompleted.isModelEvaluated()) {
            return null;
        }
        if (latestEvidenceAt != null && latestEvidenceAt.isAfter(lastCompleted.getCompletedAt())) {
            return null;
        }
        return lastCompleted.getCompletedAt().plus(RETRY_COOLDOWN);
    }

    public static View view(CapabilityPromotionReviewEntity entity, ObjectMapper objectMapper) {
        return new View(
                entity.getId(),
                entity.getStatus().name(),
                entity.getDecision().name(),
                entity.getFromRank(),
                entity.getTargetRank(),
                entity.getAppliedRank(),
                entity.getTriggerEvidenceLinkId(),
                readList(objectMapper, entity.getQuestionsJson(), JudgeDtos.Question.class),
                readList(objectMapper, entity.getAnswersJson(), JudgeDtos.Answer.class),
                readRubric(objectMapper, entity.getRubricJson()),
                entity.isModelEvaluated(),
                entity.getCreatedAt(),
                entity.getCompletedAt()
        );
    }

    private static <T> List<T> readList(ObjectMapper objectMapper, String json, Class<T> type) {
        try {
            var listType = objectMapper.getTypeFactory().constructCollectionType(List.class, type);
            List<T> values = objectMapper.readValue(json == null ? "[]" : json, listType);
            return values == null ? List.of() : values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static JudgeDtos.RubricResult readRubric(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JudgeDtos.RubricResult.class);
        } catch (Exception exception) {
            return null;
        }
    }

    public record View(
            Long id,
            String status,
            String decision,
            String fromRank,
            String targetRank,
            String appliedRank,
            Long triggerEvidenceLinkId,
            List<JudgeDtos.Question> questions,
            List<JudgeDtos.Answer> answers,
            JudgeDtos.RubricResult rubric,
            boolean modelEvaluated,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}
