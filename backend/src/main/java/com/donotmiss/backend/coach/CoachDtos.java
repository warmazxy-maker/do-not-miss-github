package com.donotmiss.backend.coach;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class CoachDtos {
    public record ChatRequest(
            @NotBlank @Size(max = 2000) String message
    ) {
    }

    public record GenerateLogRequest(
            LocalDate date
    ) {
    }

    public record ChatResponse(
            CoachMessageResponse assistantMessage,
            CoachLogResponse generatedLog,
            boolean logGenerated
    ) {
    }

    public record CoachMessageResponse(
            Long id,
            String role,
            String content,
            LocalDate messageDate,
            Instant createdAt
    ) {
        public static CoachMessageResponse from(CoachMessageEntity message) {
            return new CoachMessageResponse(
                    message.getId(),
                    message.getRole().name(),
                    message.getContent(),
                    message.getMessageDate(),
                    message.getCreatedAt()
            );
        }
    }

    public record CoachLogResponse(
            Long id,
            LocalDate logDate,
            String title,
            String summary,
            String content,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static CoachLogResponse from(CoachLogEntity log) {
            return new CoachLogResponse(
                    log.getId(),
                    log.getLogDate(),
                    log.getTitle(),
                    log.getSummary(),
                    log.getContent(),
                    splitTags(log.getTags()),
                    log.getCreatedAt(),
                    log.getUpdatedAt()
            );
        }

        private static List<String> splitTags(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.of(value.split(",")).stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
    }

    public record CoachMemoryReviewResponse(
            Long id,
            Long sourceLogId,
            String memoryType,
            String title,
            String memoryText,
            List<String> tags,
            int strength,
            int reviewCount,
            Instant lastReviewedAt,
            Instant nextReviewAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static CoachMemoryReviewResponse from(CoachMemoryReviewEntity memory) {
            return new CoachMemoryReviewResponse(
                    memory.getId(),
                    memory.getSourceLogId(),
                    memory.getMemoryType().name(),
                    memory.getTitle(),
                    memory.getMemoryText(),
                    splitTags(memory.getTags()),
                    memory.getStrength(),
                    memory.getReviewCount(),
                    memory.getLastReviewedAt(),
                    memory.getNextReviewAt(),
                    memory.getCreatedAt(),
                    memory.getUpdatedAt()
            );
        }

        private static List<String> splitTags(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.of(value.split(",")).stream()
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
    }

    public record CoachLogModelResponse(
            String title,
            String summary,
            String content,
            List<String> tags
    ) {
    }

    public record CoachReplyModelResponse(
            String reply
    ) {
    }
}
