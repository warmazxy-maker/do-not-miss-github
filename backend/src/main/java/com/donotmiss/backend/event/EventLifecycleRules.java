package com.donotmiss.backend.event;

import com.donotmiss.backend.common.ApiException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.donotmiss.backend.event.EventActorType.*;
import static com.donotmiss.backend.event.EventLifecycleAction.*;
import static com.donotmiss.backend.event.EventReviewStatus.*;

/**
 * The transition table and its guards, with no database and no side effects, so the whole
 * state machine can be read in one place and tested with plain unit tests.
 * Side effects (index, review message, history) live in {@link EventLifecycleService}.
 */
public final class EventLifecycleRules {
    /** After this many AI revision requests, the next submission is reviewed by a person. */
    public static final int AI_REVISION_LIMIT = 3;
    public static final int REASON_MAX_LENGTH = 300;

    /**
     * One row per action: which states it may start from, where it leads, who may trigger it,
     * whether a written reason is required, and whether the event must not have ended yet.
     */
    record Rule(Set<EventReviewStatus> from,
                EventReviewStatus to,
                Set<EventActorType> actors,
                boolean reasonRequired,
                boolean requiresNotEnded) {
    }

    private static final Map<EventLifecycleAction, Rule> TABLE = new EnumMap<>(EventLifecycleAction.class);

    static {
        TABLE.put(SUBMIT, new Rule(Set.of(DRAFT, NEEDS_REVISION, OFFLINE), PENDING_REVIEW, Set.of(ORGANIZER), false, true));
        TABLE.put(REVISE_PUBLISHED, new Rule(Set.of(APPROVED), PENDING_REVIEW, Set.of(ORGANIZER), false, true));
        TABLE.put(REANALYZE, new Rule(Set.of(PENDING_REVIEW, NEEDS_REVISION), PENDING_REVIEW, Set.of(ORGANIZER, OPERATOR), false, true));
        TABLE.put(AI_APPROVE, new Rule(Set.of(PENDING_REVIEW), APPROVED, Set.of(AI), false, false));
        TABLE.put(AI_REQUEST_REVISION, new Rule(Set.of(PENDING_REVIEW), NEEDS_REVISION, Set.of(AI), true, false));
        TABLE.put(MANUAL_APPROVE, new Rule(Set.of(PENDING_REVIEW, NEEDS_REVISION), APPROVED, Set.of(OPERATOR), false, true));
        TABLE.put(MANUAL_REQUEST_REVISION, new Rule(Set.of(PENDING_REVIEW), NEEDS_REVISION, Set.of(OPERATOR), true, false));
        TABLE.put(TAKE_OFFLINE, new Rule(Set.of(APPROVED), OFFLINE, Set.of(ORGANIZER, OPERATOR), true, false));
        TABLE.put(END, new Rule(Set.of(APPROVED), OFFLINE, Set.of(SYSTEM), false, false));
        TABLE.put(RELIST, new Rule(Set.of(OFFLINE), APPROVED, Set.of(OPERATOR), false, true));
        TABLE.put(DISCARD, new Rule(Set.of(DRAFT, NEEDS_REVISION), OFFLINE, Set.of(ORGANIZER), false, false));
    }

    private EventLifecycleRules() {
    }

    /** Whether the action is possible from this state at all, ignoring who asks (for showing buttons). */
    public static boolean allows(EventReviewStatus from, EventLifecycleAction action) {
        Rule rule = TABLE.get(action);
        return rule != null && rule.from().contains(from);
    }

    /**
     * Checks every guard and returns the target state, or throws:
     * 403 for the wrong actor, 409 for an action that does not fit the current state,
     * 400 for a missing reason or an event that has already ended.
     */
    public static EventReviewStatus check(EventEntity event,
                                          EventLifecycleAction action,
                                          EventActor actor,
                                          String reason,
                                          LocalDateTime now) {
        Rule rule = TABLE.get(action);
        EventReviewStatus from = event.getReviewStatus();
        if (rule == null || !rule.from().contains(from)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "活动当前是“" + from.label() + "”，不能执行这个操作（" + action.name() + "）");
        }
        if (!rule.actors().contains(actor.type())) {
            throw ApiException.forbidden("没有权限执行这个操作（" + action.name() + "）");
        }
        if (actor.type() == ORGANIZER && !Objects.equals(event.getCreatedByUserId(), actor.userId())) {
            throw ApiException.forbidden("只能操作自己发布的活动");
        }
        if (from == OFFLINE && event.getOfflineReasonCode() == EventOfflineReason.DISCARDED) {
            throw new ApiException(HttpStatus.CONFLICT, "已放弃的活动不能再操作，请重新发布");
        }
        if (rule.reasonRequired() && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("请填写原因");
        }
        if (reason != null && reason.trim().length() > REASON_MAX_LENGTH) {
            throw ApiException.badRequest("原因不能超过 " + REASON_MAX_LENGTH + " 个字");
        }
        if (rule.requiresNotEnded() && event.hasEnded(now)) {
            throw ApiException.badRequest("活动已结束，请先修改活动时间");
        }
        return rule.to();
    }

    /** Which reviewer a submission goes to, decided when the event enters PENDING_REVIEW. */
    public static EventReviewRoute routeFor(EventEntity event, EventLifecycleAction action, EventActor actor) {
        if (action == REANALYZE && actor.type() == OPERATOR) {
            // An operator asking for a fresh AI pass overrides the limit.
            return EventReviewRoute.AI;
        }
        return event.getAiRevisionCount() >= AI_REVISION_LIMIT ? EventReviewRoute.MANUAL : EventReviewRoute.AI;
    }
}
