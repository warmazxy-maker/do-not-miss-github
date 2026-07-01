package com.donotmiss.backend.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public class EventDtos {
    public record CreateEventRequest(
            @NotBlank String title,
            @NotBlank String organizationName,
            @NotBlank String category,
            @NotNull LocalDateTime startTime,
            LocalDateTime endTime,
            @NotBlank String location,
            @NotBlank String content,
            @NotBlank String benefitType,
            String skill,
            BigDecimal moneyAmount
    ) {
    }

    public record EventSearchRequest(
            String keyword,
            String category,
            String benefitType,
            String location
    ) {
    }

    public record EventResponse(
            Long id,
            String title,
            String organizationName,
            String category,
            String categoryCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            boolean expired,
            String reviewStatus,
            String reviewStatusLabel,
            String location,
            String content,
            String benefitType,
            String benefitTypeCode,
            String skill,
            BigDecimal moneyAmount,
            String createdByUserId,
            Instant createdAt
    ) {
        public static EventResponse from(EventEntity event) {
            return new EventResponse(
                    event.getId(),
                    event.getTitle(),
                    event.getOrganizationName(),
                    event.getCategory().label(),
                    event.getCategory().name(),
                    event.getStartTime(),
                    event.getEndTime(),
                    event.isExpired(),
                    event.getReviewStatus().name(),
                    event.getReviewStatus().label(),
                    event.getLocation(),
                    event.getContent(),
                    event.getBenefitType().label(),
                    event.getBenefitType().name(),
                    event.getSkill(),
                    event.getMoneyAmount(),
                    event.getCreatedByUserId(),
                    event.getCreatedAt()
            );
        }
    }
}
