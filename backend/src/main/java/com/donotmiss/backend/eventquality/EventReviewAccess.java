package com.donotmiss.backend.eventquality;

import com.donotmiss.backend.access.AccessService;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Data-dependent authorisation for an event's quality report and re-analysis: a reviewer may act
 * on any event, an organisation account only on events it published. An annotation can say "needs
 * EVENT_PUBLISH or EVENT_REVIEW" but not "and is the publisher of this event", so that part lives here.
 * (D0 introduced this with the ops allow-list; D1 moved it onto permissions.)
 */
@Component
public class EventReviewAccess {
    private final AccessService accessService;
    private final EventRepository eventRepository;

    public EventReviewAccess(AccessService accessService, EventRepository eventRepository) {
        this.accessService = accessService;
        this.eventRepository = eventRepository;
    }

    public void requirePublisherOrReviewer(HttpServletRequest request, Long eventId) {
        AccessService.Principal principal = accessService.current(request);
        if (principal.has(Permission.EVENT_REVIEW)) {
            return;
        }
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("事件不存在：" + eventId));
        if (!principal.has(Permission.EVENT_PUBLISH) || !Objects.equals(event.getCreatedByUserId(), principal.userId())) {
            throw ApiException.forbidden("只能操作自己发布的事件");
        }
    }
}
