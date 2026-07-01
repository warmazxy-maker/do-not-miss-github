package com.donotmiss.backend.abilityscore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class JudgeDtos {
    private JudgeDtos() {
    }

    public record Question(
            String id,
            String prompt,
            String focus,
            int maxScore
    ) {
    }

    public record QuestionModelResponse(
            List<Question> questions
    ) {
    }

    public record Answer(
            @NotBlank
            String questionId,
            @NotBlank
            @Size(max = 4000)
            String answer
    ) {
    }

    public record SubmitRequest(
            @NotEmpty
            @Size(max = 5)
            List<@Valid Answer> answers
    ) {
    }

    public record RubricItem(
            String questionId,
            Integer score,
            Integer maxScore,
            String feedback,
            List<String> evidence
    ) {
    }

    public record EvaluationModelResponse(
            List<RubricItem> items,
            String overallFeedback
    ) {
    }

    public record RubricResult(
            int totalScore,
            int earnedScore,
            int maxScore,
            String overallFeedback,
            List<RubricItem> items
    ) {
    }

    public record AssessmentResponse(
            Long id,
            String requestId,
            String status,
            String decision,
            Long scoreResultId,
            Long abilityStateId,
            String dimension,
            String normalizedDimension,
            List<String> triggerReasons,
            List<Question> questions,
            List<Answer> answers,
            RubricResult rubric,
            BigDecimal abilityScoreAtTrigger,
            BigDecimal confidenceBefore,
            BigDecimal confidenceDelta,
            BigDecimal confidenceAfter,
            String currentRank,
            String proposedRank,
            String reviewReason,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
