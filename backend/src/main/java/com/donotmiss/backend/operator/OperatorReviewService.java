package com.donotmiss.backend.operator;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.*;
import com.donotmiss.backend.eventquality.EventQualityService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The operator console's review side (D3): the queue (events the AI handed over, plus spot checks),
 * the detail an operator decides on, drawing spot checks, and the list of all events.
 * Every state change still goes through the D2 state machine; this class only decides what to show.
 */
@Service
public class OperatorReviewService {
    /** How many events one click on "抽检" draws. */
    static final int SPOT_CHECK_BATCH = 5;
    static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;
    private final EventStatusHistoryRepository historyRepository;
    private final EventSpotCheckRepository spotCheckRepository;
    private final EventQualityService qualityService;
    private final EventService eventService;
    private final JdbcTemplate jdbcTemplate;

    public OperatorReviewService(EventRepository eventRepository,
                                 EventStatusHistoryRepository historyRepository,
                                 EventSpotCheckRepository spotCheckRepository,
                                 EventQualityService qualityService,
                                 EventService eventService,
                                 JdbcTemplate jdbcTemplate) {
        this.eventRepository = eventRepository;
        this.historyRepository = historyRepository;
        this.spotCheckRepository = spotCheckRepository;
        this.qualityService = qualityService;
        this.eventService = eventService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * First in, first out (D3, L1). Manual items wait since they were last submitted; spot checks
     * since they were drawn. A spot check whose event has meanwhile left "published" (taken offline,
     * edited, ended) is simply not shown: there is nothing left to check.
     */
    @Transactional(readOnly = true)
    public List<OperatorDtos.QueueItem> queue() {
        List<OperatorDtos.QueueItem> items = new ArrayList<>();
        for (EventEntity event : eventRepository.findByReviewStatusAndReviewRoute(EventReviewStatus.PENDING_REVIEW, EventReviewRoute.MANUAL)) {
            Instant since = historyRepository
                    .findFirstByEventIdAndToStatusOrderByIdDesc(event.getId(), EventReviewStatus.PENDING_REVIEW)
                    .map(EventStatusHistoryEntity::getCreatedAt)
                    .orElse(event.getCreatedAt());
            items.add(item(event, OperatorDtos.QueueKind.MANUAL, since));
        }
        LocalDateTime now = LocalDateTime.now();
        for (EventSpotCheckEntity check : spotCheckRepository.findByStatusOrderByDrawnAtAsc(SpotCheckStatus.PENDING)) {
            eventRepository.findById(check.getEventId())
                    .filter(event -> event.getReviewStatus() == EventReviewStatus.APPROVED && !event.hasEnded(now))
                    .ifPresent(event -> items.add(item(event, OperatorDtos.QueueKind.SPOT_CHECK, check.getDrawnAt())));
        }
        items.sort(Comparator.comparing(OperatorDtos.QueueItem::waitingSince));
        return items;
    }

    @Transactional(readOnly = true)
    public OperatorDtos.ReviewDetail detail(Long eventId) {
        EventEntity event = eventService.getEntity(eventId);
        List<EventDtos.HistoryResponse> history = new ArrayList<>();
        List<OperatorDtos.RevisionNote> aiNotes = new ArrayList<>();
        for (EventStatusHistoryEntity row : historyRepository.findByEventIdOrderByIdAsc(eventId)) {
            history.add(EventDtos.HistoryResponse.from(row));
            if (EventLifecycleAction.AI_REQUEST_REVISION.name().equals(row.getAction())) {
                aiNotes.add(new OperatorDtos.RevisionNote(row.getReason(), row.getCreatedAt()));
            }
        }
        return new OperatorDtos.ReviewDetail(
                EventDtos.OrganizerEventResponse.from(event),
                qualityService.findReport(eventId).map(qualityService::toResponse).orElse(null),
                aiNotes,
                history,
                spotCheckRepository.findByEventIdAndStatus(eventId, SpotCheckStatus.PENDING).isPresent()
        );
    }

    /**
     * Draws the most recently AI-approved events that are still published, not ended and never
     * drawn before (D3, G3: the simple rule, "most recent first"). Events approved before D2 have no
     * history, so they cannot be told apart from manual approvals and are not drawn.
     */
    @Transactional
    public OperatorDtos.DrawResult drawSpotChecks(String operatorUserId) {
        List<Long> eventIds = jdbcTemplate.queryForList("""
                SELECT e.id FROM events e
                JOIN event_status_history h ON h.id = (
                    SELECT MAX(h2.id) FROM event_status_history h2
                    WHERE h2.event_id = e.id AND h2.to_status = 'APPROVED')
                WHERE e.review_status = 'APPROVED'
                  AND e.end_time > ?
                  AND h.action = 'AI_APPROVE'
                  AND NOT EXISTS (SELECT 1 FROM event_spot_checks s WHERE s.event_id = e.id)
                ORDER BY h.id DESC
                LIMIT ?
                """, Long.class, Timestamp.valueOf(LocalDateTime.now()), SPOT_CHECK_BATCH);
        eventIds.forEach(id -> spotCheckRepository.save(EventSpotCheckEntity.drawn(id, operatorUserId)));
        return new OperatorDtos.DrawResult(eventIds.size());
    }

    @Transactional
    public void passSpotCheck(Long eventId, String operatorUserId) {
        pendingCheck(eventId).resolve(SpotCheckStatus.PASSED, operatorUserId);
    }

    /** Something is wrong with a published event: take it offline with a reason, the organisation revises. */
    @Transactional
    public EventDtos.OrganizerEventResponse failSpotCheck(Long eventId, EventActor operator, String reason) {
        EventSpotCheckEntity check = pendingCheck(eventId);
        EventDtos.OrganizerEventResponse result = eventService.takeOffline(eventId, operator, reason);
        check.resolve(SpotCheckStatus.TAKEN_OFFLINE, operator.userId());
        return result;
    }

    private EventSpotCheckEntity pendingCheck(Long eventId) {
        return spotCheckRepository.findByEventIdAndStatus(eventId, SpotCheckStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "这个活动不在待抽检列表里，可能已被其他运营处理"));
    }

    /** All events, newest first, optionally by status and keyword. Discarded drafts are hidden unless asked for. */
    @Transactional(readOnly = true)
    public OperatorDtos.EventPage events(String status, String keyword, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        EventReviewStatus statusFilter = parseStatus(status);
        Specification<EventEntity> spec = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (statusFilter != null) {
                predicates.add(builder.equal(root.get("reviewStatus"), statusFilter));
            }
            predicates.add(builder.or(
                    builder.isNull(root.get("offlineReasonCode")),
                    builder.notEqual(root.get("offlineReasonCode"), EventOfflineReason.DISCARDED)));
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(root.get("organizationName")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<EventEntity> result = eventRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new OperatorDtos.EventPage(
                result.getContent().stream().map(EventDtos.OrganizerEventResponse::from).toList(),
                result.getTotalElements(), result.getNumber(), pageSize);
    }

    private static EventReviewStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return EventReviewStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("未知状态：" + status);
        }
    }

    private static OperatorDtos.QueueItem item(EventEntity event, OperatorDtos.QueueKind kind, Instant since) {
        return new OperatorDtos.QueueItem(event.getId(), event.getTitle(), event.getOrganizationName(),
                kind, since, event.getAiRevisionCount());
    }
}
