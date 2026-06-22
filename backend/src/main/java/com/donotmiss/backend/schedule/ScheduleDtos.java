package com.donotmiss.backend.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public class ScheduleDtos {
    public record CreateScheduleItemRequest(
            @NotBlank @Size(max = 160) String title,
            String itemType,
            Long sourceId,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime,
            @Size(max = 160) String location,
            @Size(max = 1000) String notes
    ) {
    }

    public record UpdateScheduleItemRequest(
            @NotBlank @Size(max = 160) String title,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime,
            @Size(max = 160) String location,
            @Size(max = 1000) String notes
    ) {
    }

    public record ScheduleItemResponse(
            Long id,
            String itemType,
            Long sourceId,
            String title,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String location,
            String notes,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static ScheduleItemResponse from(ScheduleItemEntity item) {
            return new ScheduleItemResponse(
                    item.getId(),
                    item.getItemType().name(),
                    item.getSourceId(),
                    item.getTitle(),
                    item.getStartTime(),
                    item.getEndTime(),
                    item.getLocation(),
                    item.getNotes(),
                    item.getStatus().name(),
                    item.getCreatedAt(),
                    item.getUpdatedAt()
            );
        }
    }

    public record ImportAiPlanRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 80) String style,
            @NotBlank @Size(max = 500) String goal,
            @NotNull List<AiPlanStepRequest> steps
    ) {
    }

    public record AiPlanStepRequest(
            int order,
            @Size(max = 40) String dateLabel,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 24) String itemType,
            Long eventId,
            @Size(max = 120) String scheduleHint,
            @Size(max = 300) String reason
    ) {
    }

    public record ImportAiPlanResponse(
            int importedCount,
            int skippedCount,
            List<String> warnings,
            List<ScheduleItemResponse> items
    ) {
    }
}
