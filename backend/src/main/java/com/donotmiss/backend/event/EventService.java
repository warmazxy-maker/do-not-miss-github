package com.donotmiss.backend.event;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import com.donotmiss.backend.organization.OrganizationService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final EventExpirationService eventExpirationService;
    private final OrganizationService organizationService;
    private final DomainEventPublisher domainEventPublisher;

    public EventService(EventRepository eventRepository,
                        EventExpirationService eventExpirationService,
                        OrganizationService organizationService,
                        DomainEventPublisher domainEventPublisher) {
        this.eventRepository = eventRepository;
        this.eventExpirationService = eventExpirationService;
        this.organizationService = organizationService;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional
    public EventDtos.EventResponse create(EventDtos.CreateEventRequest request, String userId) {
        BenefitType benefitType = BenefitType.fromText(request.benefitType());
        validateBenefitPayload(benefitType, request.skill(), request.moneyAmount());
        validateTimeRange(request.startTime(), request.endTime());

        organizationService.ensureOrganization(request.organizationName());

        EventEntity event = new EventEntity();
        event.setTitle(request.title().trim());
        event.setOrganizationName(request.organizationName().trim());
        event.setCategory(EventCategory.fromText(request.category()));
        event.setStartTime(request.startTime());
        event.setEndTime(normalizeEndTime(request.startTime(), request.endTime()));
        event.setExpired(event.getEndTime().isBefore(java.time.LocalDateTime.now()));
        event.setLocation(request.location().trim());
        event.setContent(request.content().trim());
        event.setBenefitType(benefitType);
        event.setSkill(blankToNull(request.skill()));
        event.setMoneyAmount(request.moneyAmount());
        event.setCreatedByUserId(userId);

        EventEntity saved = eventRepository.save(event);
        domainEventPublisher.publishEventIndex(saved.getId(), DomainEventMessages.EVENT_INDEX_UPSERT);
        return EventDtos.EventResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public EventEntity getEntity(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
    }

    @Transactional
    public List<EventDtos.EventResponse> search(EventDtos.EventSearchRequest request) {
        eventExpirationService.expireOverdueEvents();
        return searchEntities(request).stream()
                .map(EventDtos.EventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventEntity> searchEntities(EventDtos.EventSearchRequest request) {
        Specification<EventEntity> spec = buildSpecification(request);
        return eventRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "startTime"));
    }

    @Transactional
    public void delete(Long eventId, String userId) {
        EventEntity event = getEntity(eventId);

        if (!Objects.equals(event.getCreatedByUserId(), userId)) {
            throw ApiException.forbidden("只能删除自己发布的事件");
        }

        eventRepository.delete(event);
        domainEventPublisher.publishEventIndex(eventId, DomainEventMessages.EVENT_INDEX_DELETE);
    }

    private Specification<EventEntity> buildSpecification(EventDtos.EventSearchRequest request) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.isFalse(root.get("expired")));

            if (request.keyword() != null && !request.keyword().isBlank()) {
                String pattern = "%" + request.keyword().trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(root.get("organizationName")), pattern),
                        builder.like(builder.lower(root.get("location")), pattern),
                        builder.like(builder.lower(root.get("content")), pattern),
                        builder.like(builder.lower(root.get("skill")), pattern)
                ));
            }

            if (request.category() != null && !request.category().isBlank()) {
                predicates.add(builder.equal(root.get("category"), EventCategory.fromText(request.category())));
            }

            if (request.location() != null && !request.location().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("location")), "%" + request.location().trim().toLowerCase() + "%"));
            }

            if (request.benefitType() != null && !request.benefitType().isBlank()) {
                BenefitType benefitType = BenefitType.fromText(request.benefitType());
                if (benefitType == BenefitType.SKILL) {
                    predicates.add(root.get("benefitType").in(BenefitType.SKILL, BenefitType.BOTH));
                } else if (benefitType == BenefitType.MONEY) {
                    predicates.add(root.get("benefitType").in(BenefitType.MONEY, BenefitType.BOTH));
                } else {
                    predicates.add(builder.equal(root.get("benefitType"), BenefitType.BOTH));
                }
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void validateBenefitPayload(BenefitType benefitType, String skill, BigDecimal moneyAmount) {
        if (benefitType.hasSkill() && (skill == null || skill.isBlank())) {
            throw ApiException.badRequest("技能经验不能为空");
        }
        if (benefitType.hasMoney() && moneyAmount == null) {
            throw ApiException.badRequest("金钱报酬不能为空");
        }
    }

    private void validateTimeRange(java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        java.time.LocalDateTime normalizedEndTime = normalizeEndTime(startTime, endTime);
        if (!normalizedEndTime.isAfter(startTime)) {
            throw ApiException.badRequest("结束时间必须晚于开始时间。");
        }
    }

    private java.time.LocalDateTime normalizeEndTime(java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        return endTime == null ? startTime.plusHours(2) : endTime;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
