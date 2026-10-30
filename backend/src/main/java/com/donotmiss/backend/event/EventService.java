package com.donotmiss.backend.event;

import com.donotmiss.backend.auth.UserEntity;
import com.donotmiss.backend.auth.UserRepository;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.organization.OrganizationMembershipService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final EventLifecycleService lifecycleService;
    private final EventStatusHistoryRepository historyRepository;
    private final OrganizationMembershipService organizationMembershipService;
    private final UserRepository userRepository;

    public EventService(EventRepository eventRepository,
                        EventLifecycleService lifecycleService,
                        EventStatusHistoryRepository historyRepository,
                        OrganizationMembershipService organizationMembershipService,
                        UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.lifecycleService = lifecycleService;
        this.historyRepository = historyRepository;
        this.organizationMembershipService = organizationMembershipService;
        this.userRepository = userRepository;
    }

    /**
     * Red dot on "我的事件" (D3, L5): how many of this publisher's events the AI or an operator
     * changed since the page was last opened. Counted from the status history, so it covers every
     * kind of change (approved, revision requested, taken offline) without a notification table.
     */
    @Transactional(readOnly = true)
    public long attentionCount(String userId) {
        Instant since = userRepository.findByPublicUserId(userId)
                .map(UserEntity::getEventsSeenAt)
                .orElse(null);
        return historyRepository.countChangedEventsSince(userId,
                List.of(EventActorType.AI, EventActorType.OPERATOR),
                since == null ? Instant.EPOCH : since);
    }

    @Transactional
    public void markEventsSeen(String userId) {
        userRepository.findByPublicUserId(userId).ifPresent(user -> user.setEventsSeenAt(Instant.now()));
    }

    /** Saves a new event as DRAFT ("已保存"), then submits it unless the organiser only wants to save. */
    @Transactional
    public EventDtos.OrganizerEventResponse create(EventDtos.CreateEventRequest request, String userId) {
        EventEntity event = new EventEntity();
        event.setCreatedByUserId(userId);
        applyFields(event, request, userId);
        EventEntity saved = eventRepository.save(event);

        EventActor organizer = EventActor.organizer(userId);
        lifecycleService.recordCreated(saved, organizer);
        if (!Boolean.FALSE.equals(request.submit())) {
            saved = lifecycleService.transition(saved, EventLifecycleAction.SUBMIT, organizer, null);
        }
        return EventDtos.OrganizerEventResponse.from(saved);
    }

    /**
     * Edits an event. Allowed in every state except "审核中" (the reviewer would be judging content
     * that changes underneath) and a discarded event. Editing a published event takes it back to
     * review: REVISE_PUBLISHED, and students see "已下架：活动重新上架审核中" until it is approved again.
     */
    @Transactional
    public EventDtos.OrganizerEventResponse update(Long eventId, EventDtos.CreateEventRequest request, String userId) {
        EventEntity event = getEntity(eventId);
        if (!Objects.equals(event.getCreatedByUserId(), userId)) {
            throw ApiException.forbidden("只能修改自己发布的活动");
        }
        if (event.getReviewStatus() == EventReviewStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "活动正在审核中，审核结束后才能修改");
        }
        if (event.getOfflineReasonCode() == EventOfflineReason.DISCARDED) {
            throw new ApiException(HttpStatus.CONFLICT, "已放弃的活动不能修改，请重新发布");
        }
        EventReviewStatus before = event.getReviewStatus();
        applyFields(event, request, userId);

        EventActor organizer = EventActor.organizer(userId);
        EventEntity saved = eventRepository.save(event);
        if (before == EventReviewStatus.APPROVED) {
            saved = lifecycleService.transition(saved, EventLifecycleAction.REVISE_PUBLISHED, organizer, null);
        } else if (Boolean.TRUE.equals(request.submit())) {
            saved = lifecycleService.transition(saved, EventLifecycleAction.SUBMIT, organizer, null);
        }
        return EventDtos.OrganizerEventResponse.from(saved);
    }

    @Transactional
    public EventDtos.OrganizerEventResponse submit(Long eventId, String userId) {
        EventEntity event = getEntity(eventId);
        return EventDtos.OrganizerEventResponse.from(
                lifecycleService.transition(event, EventLifecycleAction.SUBMIT, EventActor.organizer(userId), null));
    }

    /** Organisation (own event) or operator; reservations are kept, students see the reason. */
    @Transactional
    public EventDtos.OrganizerEventResponse takeOffline(Long eventId, EventActor actor, String reason) {
        EventEntity event = getEntity(eventId);
        return EventDtos.OrganizerEventResponse.from(
                lifecycleService.transition(event, EventLifecycleAction.TAKE_OFFLINE, actor, reason));
    }

    /** Operators only: straight back to published. Organisations edit and resubmit instead. */
    @Transactional
    public EventDtos.OrganizerEventResponse relist(Long eventId, EventActor actor) {
        EventEntity event = getEntity(eventId);
        return EventDtos.OrganizerEventResponse.from(
                lifecycleService.transition(event, EventLifecycleAction.RELIST, actor, null));
    }

    /**
     * What used to be a hard delete. Nothing is ever removed from the database: reservations and
     * achievements still point at the row. Only events that were never published can be discarded;
     * a published one is taken offline with a reason instead.
     */
    @Transactional
    public void discard(Long eventId, String userId) {
        EventEntity event = getEntity(eventId);
        if (event.getReviewStatus() == EventReviewStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "已上架的活动请使用“下架”，并填写原因");
        }
        lifecycleService.transition(event, EventLifecycleAction.DISCARD, EventActor.organizer(userId), null);
    }

    @Transactional(readOnly = true)
    public List<EventDtos.HistoryResponse> history(Long eventId, EventActor actor) {
        EventEntity event = getEntity(eventId);
        if (actor.type() == EventActorType.ORGANIZER && !Objects.equals(event.getCreatedByUserId(), actor.userId())) {
            throw ApiException.forbidden("只能查看自己发布的活动");
        }
        return historyRepository.findByEventIdOrderByIdAsc(eventId).stream()
                .map(EventDtos.HistoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventEntity getEntity(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
    }

    /** Pure read: no "expire overdue events first" any more, the time filter is part of the query. */
    @Transactional(readOnly = true)
    public List<EventDtos.EventResponse> search(EventDtos.EventSearchRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return searchEntities(request, now).stream()
                .map(event -> EventDtos.EventResponse.from(event, now))
                .toList();
    }

    /** Discarded drafts are hidden from the organisation's own list. */
    @Transactional(readOnly = true)
    public List<EventDtos.OrganizerEventResponse> mine(String userId) {
        return eventRepository.findByCreatedByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(event -> event.getOfflineReasonCode() != EventOfflineReason.DISCARDED)
                .map(EventDtos.OrganizerEventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventEntity> searchEntities(EventDtos.EventSearchRequest request) {
        return searchEntities(request, LocalDateTime.now());
    }

    private List<EventEntity> searchEntities(EventDtos.EventSearchRequest request, LocalDateTime now) {
        Specification<EventEntity> spec = buildSpecification(request, now);
        return eventRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "startTime"));
    }

    private void applyFields(EventEntity event, EventDtos.CreateEventRequest request, String userId) {
        BenefitType benefitType = BenefitType.fromText(request.benefitType());
        validateBenefitPayload(benefitType, request.skill(), request.moneyAmount());
        validateTimeRange(request.startTime(), request.endTime());
        organizationMembershipService.ensureCanPublish(userId, request.organizationName().trim());

        event.setTitle(request.title().trim());
        event.setOrganizationName(request.organizationName().trim());
        event.setCategory(EventCategory.fromText(request.category()));
        event.setStartTime(request.startTime());
        event.setEndTime(normalizeEndTime(request.startTime(), request.endTime()));
        event.setLocation(request.location().trim());
        event.setContent(request.content().trim());
        event.setBenefitType(benefitType);
        event.setSkill(blankToNull(request.skill()));
        event.setMoneyAmount(request.moneyAmount());
    }

    private Specification<EventEntity> buildSpecification(EventDtos.EventSearchRequest request, LocalDateTime now) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("reviewStatus"), EventReviewStatus.APPROVED));
            predicates.add(builder.greaterThan(root.get("endTime"), now));

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

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime normalizedEndTime = normalizeEndTime(startTime, endTime);
        if (!normalizedEndTime.isAfter(startTime)) {
            throw ApiException.badRequest("结束时间必须晚于开始时间。");
        }
    }

    private LocalDateTime normalizeEndTime(LocalDateTime startTime, LocalDateTime endTime) {
        return endTime == null ? startTime.plusHours(2) : endTime;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
