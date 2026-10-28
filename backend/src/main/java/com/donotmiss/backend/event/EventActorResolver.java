package com.donotmiss.backend.event;

import com.donotmiss.backend.access.AccessService;
import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Turns the logged-in user into a lifecycle actor. Reviewers act as OPERATOR on any event;
 * publishers act as ORGANIZER, and the state machine then checks that the event is theirs.
 */
@Component
public class EventActorResolver {
    private final AccessService accessService;

    public EventActorResolver(AccessService accessService) {
        this.accessService = accessService;
    }

    public EventActor resolve(HttpServletRequest request) {
        AccessService.Principal principal = accessService.current(request);
        if (principal.has(Permission.EVENT_REVIEW)) {
            return EventActor.operator(principal.userId());
        }
        if (principal.has(Permission.EVENT_PUBLISH)) {
            return EventActor.organizer(principal.userId());
        }
        throw ApiException.forbidden("没有操作活动的权限");
    }
}
