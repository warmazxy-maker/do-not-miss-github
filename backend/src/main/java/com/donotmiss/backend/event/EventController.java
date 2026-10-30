package com.donotmiss.backend.event;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;
    private final CurrentUser currentUser;
    private final EventActorResolver actorResolver;

    public EventController(EventService eventService, CurrentUser currentUser, EventActorResolver actorResolver) {
        this.eventService = eventService;
        this.currentUser = currentUser;
        this.actorResolver = actorResolver;
    }

    /**
     * 社会端发布事件：先存为“已保存”，submit 不为 false 时直接提交审核。
     */
    @PostMapping
    @RequirePermission(value = Permission.EVENT_PUBLISH, action = "event.publish")
    public EventDtos.OrganizerEventResponse create(@Valid @RequestBody EventDtos.CreateEventRequest request,
                                                   HttpServletRequest servletRequest) {
        return eventService.create(request, currentUser.id(servletRequest));
    }

    /**
     * 学生端普通搜索：关键词、分类、地点、收益类型都在这里处理。
     */
    @GetMapping
    public List<EventDtos.EventResponse> search(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String benefitType,
                                                @RequestParam(required = false) String location) {
        return eventService.search(new EventDtos.EventSearchRequest(keyword, category, benefitType, location));
    }

    @GetMapping("/mine")
    @RequirePermission(Permission.EVENT_PUBLISH)
    public List<EventDtos.OrganizerEventResponse> mine(HttpServletRequest request) {
        return eventService.mine(currentUser.id(request));
    }

    @GetMapping("/mine/attention")
    @RequirePermission(Permission.EVENT_PUBLISH)
    public java.util.Map<String, Long> attention(HttpServletRequest request) {
        return java.util.Map.of("count", eventService.attentionCount(currentUser.id(request)));
    }

    @PostMapping("/mine/seen")
    @RequirePermission(Permission.EVENT_PUBLISH)
    public void seen(HttpServletRequest request) {
        eventService.markEventsSeen(currentUser.id(request));
    }

    @PutMapping("/{eventId}")
    @RequirePermission(value = Permission.EVENT_PUBLISH, action = "event.edit")
    public EventDtos.OrganizerEventResponse update(@PathVariable Long eventId,
                                                   @Valid @RequestBody EventDtos.CreateEventRequest request,
                                                   HttpServletRequest servletRequest) {
        return eventService.update(eventId, request, currentUser.id(servletRequest));
    }

    @PostMapping("/{eventId}/submit")
    @RequirePermission(value = Permission.EVENT_PUBLISH, action = "event.submit")
    public EventDtos.OrganizerEventResponse submit(@PathVariable Long eventId, HttpServletRequest request) {
        return eventService.submit(eventId, currentUser.id(request));
    }

    @PostMapping("/{eventId}/offline")
    @RequirePermission(value = {Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW}, action = "event.offline")
    public EventDtos.OrganizerEventResponse takeOffline(@PathVariable Long eventId,
                                                        @Valid @RequestBody EventDtos.ReasonRequest body,
                                                        HttpServletRequest request) {
        return eventService.takeOffline(eventId, actorResolver.resolve(request), body.reason());
    }

    @PostMapping("/{eventId}/relist")
    @RequirePermission(value = Permission.EVENT_REVIEW, action = "event.relist")
    public EventDtos.OrganizerEventResponse relist(@PathVariable Long eventId, HttpServletRequest request) {
        return eventService.relist(eventId, actorResolver.resolve(request));
    }

    @GetMapping("/{eventId}/history")
    @RequirePermission({Permission.EVENT_PUBLISH, Permission.EVENT_REVIEW})
    public List<EventDtos.HistoryResponse> history(@PathVariable Long eventId, HttpServletRequest request) {
        return eventService.history(eventId, actorResolver.resolve(request));
    }

    /** Soft: a never-published event is discarded (OFFLINE / DISCARDED); nothing is deleted. */
    @DeleteMapping("/{eventId}")
    @RequirePermission(value = Permission.EVENT_PUBLISH, action = "event.discard")
    public void discard(@PathVariable Long eventId, HttpServletRequest request) {
        eventService.discard(eventId, currentUser.id(request));
    }
}
